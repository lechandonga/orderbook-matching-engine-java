# 订单簿内存撮合引擎（Java / Spring Boot）

在进程内存中维护订单簿、账户资金与持仓，所有状态迁移先写本地事件日志
（JSONL + checksum），重启时逐笔重放恢复。支持限价单 / 市价单、撤单、
前置风控、外部订单号幂等，HTTP 入口与进程内入口共用同一引擎、判定一致。

## 1. 功能与业务规则

### 1.1 订单类型与状态

- `LIMIT`：限价单。必须带正价格；成交不完的剩余数量挂入订单簿。
- `MARKET`：市价单。不允许带价格，按 IOC（Immediate-or-Cancel）语义执行：
  立即与对手盘成交，剩余数量直接撤销（`MARKET_IOC_REMAINDER`），不入簿；
  提交时对手盘无报价则直接拒绝（`MARKET_NOT_TRADEABLE`）。
- 状态机：`NEW → PARTIALLY_FILLED → FILLED`，任意活动状态可 `CANCELED`；
  未被受理的订单为 `REJECTED`（不入活动订单表，仅留拒单事件）。

### 1.2 撮合优先级

- **价格优先**：买单优先与最低卖价成交，卖单优先与最高买价成交；成交价取
  对手挂单价（maker price），taker 限价只用于判断价格是否穿越。
- **同价时间优先（FIFO）**：同一价格档位用队列维护，引擎为每个受理订单分配
  单调递增 `seq`，`seq` 小者先成交；maker 部分成交后保持队首位置。
- 内部全部金额/价格使用 1e-8 定点整数，数量为整数，避免浮点误差。

### 1.3 前置风控（在任何账本变更之前执行）

校验顺序固定，拒绝原因可区分：

1. `MISSING_FIELD`：订单号 / 账户 / 标的 / 方向 / 类型 / 数量缺失或为空。
2. `INVALID_QUANTITY`：数量不在 `[matching.min-order-quantity,
   matching.max-order-quantity]` 内。
3. `INVALID_PRICE`：限价单价格为空、≤0、超过 `matching.max-price` 或精度超过 1e-8；
   市价单带价返回 `MARKET_PRICE_NOT_ALLOWED`。
4. `UNKNOWN_ACCOUNT`：账户不存在。
5. 买单：按最坏名义金额（限价单 = 限价×数量；市价单 = 当前对手盘逐档累计的
   最坏成交额）依次校验单笔敞口上限 `EXPOSURE_LIMIT_EXCEEDED` 与
   账户可用现金（现金总额 − 已预留）`INSUFFICIENT_CASH`。
6. 卖单：按参考名义金额（限价取限价，市价取对手最优价）校验敞口上限，
   再校验可用持仓（总持仓 − 活动卖单冻结量）`INSUFFICIENT_POSITION`。

风控通过后才预留资源：买单按最坏成交额预留现金，卖单冻结相应持仓。
**任何拒绝都不会产生资金/持仓变更**。限价买单成交价优于限价时，立即把
“限价×成交量 − 实际成交额”的差额从预留中释放；撤单释放剩余预留。

### 1.4 幂等规则

- `clientOrderId` 是全局唯一外部订单号。
- 相同订单号再次提交：账户、标的、方向、类型、价格（限价单）、数量完全一致时，
  返回首次处理结论（响应中 `idempotentReplay=true`），**不再次撮合、不再次扣减**；
  拒单同样幂等，重复提交返回同一个 `rejectReason`。
- 同订单号但关键字段不一致：返回 `DUPLICATE_ORDER_ID` 冲突拒绝，不覆盖首次结果。
- HTTP 接口（`POST /api/orders`）与内部调用入口
  （`InternalTradingGateway#submitOrder`）都只委托同一个 `MatchingEngine`，
  因此两处入口的受理、风控、幂等判定逐字节一致；HTTP 额外把拒绝原因映射为
  4xx 状态码（400 参数错误 / 404 订单不存在 / 409 冲突 / 422 业务风控拒绝）。

### 1.5 并发与守恒

- `MatchingEngine` 对提交 / 撤单 / 查询统一使用单把私有锁串行化，
  订单簿与账户只在锁内变更，杜绝超卖、重复成交与丢单。
- 守恒关系：买单成交额 = 卖单到账额；买方现金减少、持仓增加，
  卖方现金增加、持仓减少；撤单仅把预留转回可用。

## 2. 事件记录格式与兼容要求

事件文件由 `matching.ledger-path` 指定（默认 `data/events.jsonl`），
每行一条 UTF-8 JSON 事件：

```json
{"seq":12,"type":"TRADE","payload":{...},"checksum":"<64位sha256十六进制>"}
```

- `seq`：全局单调递增，从 1 开始，必须连续。
- `type`：`ACCOUNT_OPENED | ORDER_ACCEPTED | ORDER_REJECTED | TRADE | ORDER_CANCELED`。
- `payload`：事件字段（字符串/整数/布尔/嵌套对象）。
  - `ORDER_ACCEPTED`：clientOrderId、accountId、symbol、side、type、price（定点整数）、
    quantity、seq、reserveNotional。
  - `TRADE`：tradeId、takerOrderId、makerOrderId、双方账户、symbol、takerSide、
    quantity、price、notional。
  - `ORDER_CANCELED`：clientOrderId、reason（`USER_CANCEL` /
    `MARKET_IOC_REMAINDER`）、filledQuantity、canceledQuantity、releasedNotional。
  - `ORDER_REJECTED`：clientOrderId 与 `reason`（拒单原因枚举名）。
- `checksum = SHA-256(seq + "|" + type + "|" + canonical(payload))`，
  `canonical(payload)` 为按 key 字典序递归排序、无多余空白的 JSON；
  空负载按 `{}` 计算。

**恢复与容错**

- 启动时 `EventLog#replay()` 顺序读出并校验全部记录，重放后再按 `seq` 重建订单簿，
  保证活动单的价格优先 / 时间优先队列与宕机前一致。
- 出现以下任一情况抛出 `LedgerCorruptedException`，**应用启动失败，绝不静默跳过**：
  空行/截断、非法 JSON、必需字段缺失或类型错误、未知事件类型、
  `seq` 不连续（记录缺失）、checksum 不匹配（记录被篡改/损坏）。
- 兼容要求：旧事件只允许新增可选 payload 字段（读取时容忍未知字段），
  既有必需字段不得删除或改类型；新增事件类型必须同步实现重放分支。

## 3. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/orders` | 下单（限价/市价），成功 200，拒绝按原因返回 400/409/422 |
| POST | `/api/orders/cancel` | 撤单；不存在 404，已终结/账户不符 409 |
| GET  | `/api/orders/{clientOrderId}` | 查询订单状态 |
| GET  | `/api/orders/accounts/{accountId}` | 账户现金/预留/持仓快照 |
| GET  | `/api/orders/books/{symbol}/depth` | 买卖簿挂单数量 |

下单请求示例：

```bash
curl -XPOST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"clientOrderId":"o-1","accountId":"alice","symbol":"AAPL",
       "side":"BUY","type":"LIMIT","price":150,"quantity":5}'
```

## 4. 本地验证

环境要求：JDK 21+、Maven 3.9+。

```bash
mvn test          # 全部 18 个业务用例，日志含 [业务覆盖] 用例组/用例/汇总
mvn package       # 构建可执行 jar
mvn spring-boot:run          # 启动（默认事件文件 data/events.jsonl，首次自动播种账户）
```

默认播种账户（`application.properties` 可改）：

| 账户 | 初始现金 | 单笔敞口上限 | AAPL 持仓 |
| --- | --- | --- | --- |
| alice | 100000 | 100000 | 100 |
| bob   | 50000 | 50000 | 50 |

### 测试覆盖的业务（日志中以“业务-xxx 用例组”打印逐条结果）

- 订单生命周期：全部成交、部分成交残量入簿、撤单与重复撤单、市价 IOC、无盘口拒单。
- 前置风控：字段缺失/价格与数量越界/市价带价、超额度、超敞口、持仓不足与冻结。
- 撮合优先级：多档价格优先、同价 FIFO 时间优先。
- 幂等与恢复：重复提交返回首次结论、字段冲突、拒单幂等、重启重放逐笔一致、
  事件截断与篡改均显式报错。
- 并发：100 买家争抢 50 股卖盘不超卖；撤单线程与卖单并发冲击下
  “成交量 + 撤销量 = 初始量”，持仓守恒。
- 双入口一致：同一请求在 HTTP 与内部网关得到相同结论与拒绝原因码。

想验证损坏日志：停服后删除或改动 `data/events.jsonl` 的任意一行
（或把某行的 payload 数字改掉但保留 checksum），重新启动即可看到
`LedgerCorruptedException` 导致的启动失败与明确行号/序号提示。
