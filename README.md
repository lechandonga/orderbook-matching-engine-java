# 订单簿撮合引擎

## 执行方式

下单接口为 `POST /orders`，在原有限价单、市价单字段上新增：

- `timeInForce`：`GTC`（默认）、`IOC`、`FOK`。
- `expiresAt`：UTC 截止时间，例如 `2026-10-06T00:00:10Z`。

语义如下：

- `GTC`：限价单未成交部分挂单；市价单不允许剩余，无法立即成交的部分按无流动性拒单。
- `IOC`：限价单、市价单均支持，立即成交可成交部分，剩余部分撤销并释放预留资金或持仓。
- `FOK`：限价单、市价单均支持，只有当前订单簿能满足全部数量才提交并成交；否则返回 `WOULD_NOT_FILL`。
- `FOK` 流动性不足发生在任何接受或成交事件之前；不会产生部分成交，订单簿、成交事件、资金和持仓均保持未下单状态。该原因独立于 `INSUFFICIENT_AVAILABLE_CASH`、`INSUFFICIENT_AVAILABLE_POSITION` 和 `EXPOSURE_LIMIT_EXCEEDED`。
- `expiresAt` 只适用于可挂单的 `GTC` 限价单，不能与 `IOC`/`FOK` 同时使用。

## 结论判定边界

撮合完成后按“成交后剩余数量”一次性判定终态，`FILLED` 优先且不带任何拒单原因；只有确有剩余时才进入撤销或拒单：

| 执行方式 | 全部成交（剩余 0） | 部分成交（剩余 > 0） | 完全未成交 |
| --- | --- | --- | --- |
| `GTC` 限价单 | `FILLED`，不写挂单事件 | 剩余写入 `ORDER_ADDED_TO_BOOK` 挂单，状态 `PARTIALLY_FILLED` | 写挂单事件，状态 `NEW` |
| `IOC`（限价/市价） | `FILLED`，**不写撤销事件** | 写 `ORDER_CANCELLED`，状态 `CANCELLED`，无拒单原因 | 整单 `CANCELLED`，无拒单原因 |
| `GTC` 市价单 | `FILLED`，**不写拒单事件** | 已成部分交收，剩余写 `ORDER_REJECTED` + `NO_LIQUIDITY`，状态 `REJECTED` | 整单 `REJECTED` + `NO_LIQUIDITY` |
| `FOK`（限价/市价） | `FILLED`，**不写挂单/撤销/拒单事件** | 不会发生：流动性不足即整单拒单 | `REJECTED` + `WOULD_NOT_FILL`，不留接受/成交痕迹 |

资金与持仓的释放只与剩余数量有关：

- 全成时预留已在成交结算中全部释放，终态不再重复释放，`reservedCash`/`reservedPosition` 归零。
- IOC 部分成交只释放剩余数量对应的买单预留资金或卖单预留持仓；市价单部分成交同理（剩余按无流动性拒单时释放）。
- 完全未成交时释放整单预留；FOK 在任何接受事件之前检查流动性，预留释放后账本与未下单完全一致。

## 截止时间订单

带 `expiresAt` 的订单在截止时刻仍未成交完时，写入 `ORDER_EXPIRED` 事件并进入 `EXPIRED` 状态：

- 已成交部分和成交账本保持不变。
- 只释放剩余数量对应的买单预留资金或卖单预留持仓。
- 到期检查、提交撮合、撤单共用同一个服务临界区，按进入顺序原子决定先后。
- 到期终结后再次扫描或重启重放，不会重复到期，也不会重放成成交扣减。

生产环境由调度器按 `matching.expiry-check-ms`（默认 100ms）扫描。测试和运维可调用 `POST /maintenance/expire?now=...` 传入固定时间，以确定性方式验证到期，不需要真实等待。

## 幂等与恢复

相同 `clientOrderId` 的重复提交：

- 请求内容一致时，外部 HTTP 调用和内部服务调用都返回首次结论，结果中 `replay=true`。
- 请求内容不一致时返回 `IDEMPOTENCY_CONFLICT`。
- 重启后先严格重放事件日志，再处理重复请求，因此状态、成交数量、拒单原因和首次一致。

一次提交产生的接受、成交、挂单/撤销/拒单事件按连续 `seq` 组装并一次写入；FOK 全不成只写一条拒单事件。

事件与内存终态的对应规则：

- 一张订单要么“接受 + 成交（+ 挂单/撤销/拒单）”，要么只有一条拒单事件；同一张单不会同时出现成交记录和与成交数量矛盾的撤销/拒单记录。
- 全成订单的事件序列只包含 `ORDER_ACCEPTED` 与 `ORDER_TRADED`（可能多条），不追加 `ORDER_ADDED_TO_BOOK`、`ORDER_CANCELLED` 或 `ORDER_REJECTED`。
- 终态完全由事件重放推导：首次返回、重复提交（`replay=true`）、重启重放后再次提交得到的 `status`、`filledQty`、`remainingQty`、`rejectReason` 必须一致。
- 重放只按事件内容重建订单与账本，不会因为内存对象尚未更新而补出多余终态事件。

## 事件记录兼容范围

事件仍为 JSON Lines、`version=1`、每行带 payload CRC32。旧记录不做转换即可读取：

- 旧 payload 中缺少 `timeInForce` 时按 `GTC` 处理。
- 旧 payload 中缺少 `expiresAt`、`remainingQty` 时按对应空值处理。
- 新事件 `ORDER_EXPIRED` 可与旧事件在同一文件中交错存放。

严格性保持不变：不支持的版本、未知事件类型、缺失 `type/payload`、JSON 损坏、空行、CRC 错误、`seq` 缺失或乱序都会使启动/读取失败，不会静默跳过，也不会只回放一部分。

## 本地验证

运行全部测试：

```bash
mvn test
```

测试日志中的 `[业务:...]` 行会显示覆盖的业务、每个业务的用例数、成功/失败数和关键结果。主要覆盖：

- IOC 部分成交、剩余撤销与预留释放。
- IOC 限价/市价单的全成、部分成交、完全未成交（含买/卖两边）。
- 市价单的全成、部分成交（剩余 `NO_LIQUIDITY`）、完全未成交。
- 全成订单在首次返回、重复提交、重启重放三条路径下结论一致，且事件记录中无矛盾的撤销/拒单/挂单记录。
- FOK 全部成交、流动性不足原子拒单、幂等重复提交与重启后重复提交。
- 截止时间订单部分成交后到期、剩余预留释放、重启后不重复失效。
- 新旧事件记录混合重放，以及损坏记录严格失败。

示例：

```bash
curl -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "clientOrderId":"buy-ioc-1",
    "accountId":"buyer",
    "symbol":"BTCUSD",
    "side":"BUY",
    "type":"LIMIT",
    "price":100,
    "qty":10,
    "timeInForce":"IOC"
  }'
```
