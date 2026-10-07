# 订单簿撮合引擎

## 执行方式

下单接口为 `POST /orders`，在原有限价单、市价单字段上新增：

- `timeInForce`：`GTC`（默认）、`IOC`、`FOK`。
- `expiresAt`：UTC 截止时间，例如 `2026-10-06T00:00:10Z`。

语义如下：

- 判定边界以撮合后的剩余数量（`qty - filledQty`）为准：
  - 剩余为 0：`FILLED`（全成），不带任何拒单原因，事件记录中只包含 `ORDER_ACCEPTED` 与一条或多条 `ORDER_TRADED`；不再补写撤销、拒单或挂单事件。
  - 剩余大于 0：按下述执行方式产生“部分成交 + 终结剩余”或“整单未成交 + 终结”，已成部分的交收与账本不受影响。
- `GTC`：限价单未成交部分挂单；市价单不允许剩余，无法立即成交的部分按无流动性拒单。
- `IOC`：限价单、市价单均支持，立即成交可成交部分，剩余部分撤销并释放预留资金或持仓。
- `FOK`：限价单、市价单均支持，只有当前订单簿能满足全部数量才提交并成交；否则返回 `WOULD_NOT_FILL`。
- `FOK` 流动性不足发生在任何接受或成交事件之前；不会产生部分成交，订单簿、成交事件、资金和持仓均保持未下单状态。该原因独立于 `INSUFFICIENT_AVAILABLE_CASH`、`INSUFFICIENT_AVAILABLE_POSITION` 和 `EXPOSURE_LIMIT_EXCEEDED`。
- `expiresAt` 只适用于可挂单的 `GTC` 限价单，不能与 `IOC`/`FOK` 同时使用。

各类组合的终态结论：

| 执行方式 | 可成交量 = 委托量（全成） | 部分可成交 | 完全无可成交 |
| --- | --- | --- | --- |
| 限价 `GTC` | `FILLED` | 已成部分交收，剩余挂单（`NEW`/`PARTIALLY_FILLED`） | 整单挂单（`NEW`） |
| 限价/市价 `IOC` | `FILLED`，无拒单原因 | `CANCELLED`（仅撤销剩余、仅释放剩余预留） | 整单 `CANCELLED` |
| 市价 `GTC` | `FILLED`，无拒单原因 | `REJECTED / NO_LIQUIDITY`（已成部分不动） | 整单 `REJECTED / NO_LIQUIDITY` |
| 限价/市价 `FOK` | `FILLED` | 不允许：整单 `REJECTED / WOULD_NOT_FILL` 且不留接受、成交痕迹 | 整单 `REJECTED / WOULD_NOT_FILL` 且不留痕迹 |

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

### 事件记录与回放的终态规则

- 终态由事件序列唯一决定，重放结果必须与首次提交一致：
  - 全成订单的事件序列为 `ORDER_ACCEPTED` + 一条或多条 `ORDER_TRADED`，重放后为 `FILLED`；不会出现与全成矛盾的 `ORDER_CANCELLED`、`ORDER_REJECTED`，也不会为已全成的单补写 `ORDER_ADDED_TO_BOOK`。
  - IOC 部分成交/全不成时，成交事件（如有）之后追加一条 `ORDER_CANCELLED`，重放后为 `CANCELLED`，且只释放剩余数量对应的预留。
  - 市价单部分成交/全不成时，成交事件（如有）之后追加一条带 `NO_LIQUIDITY` 的 `ORDER_REJECTED`，重放后为 `REJECTED`，已成部分和账本不变。
  - 限价 GTC 仅有剩余时才写 `ORDER_ADDED_TO_BOOK`；FOK 不足只写一条 `ORDER_REJECTED / WOULD_NOT_FILL`。
- 相同 `clientOrderId` 的重复提交（无论是否重启）都直接返回事件重放得到的首次结论：全成单在首次返回、重启重放、重复提交三条路径下均为 `FILLED` 且 `rejectReason` 为空，结果中 `replay=true`（首次为 `false`）。

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
- IOC 刚好全成（限价买、限价卖、市价买）为 `FILLED` 且无撤销记录；IOC 部分成交只撤销/释放剩余；IOC 全不成整单撤销、无成交事件。
- 市价单刚好全成（买、卖两边）为 `FILLED` 且无拒单记录；市价单部分成交对未成交部分按 `NO_LIQUIDITY` 拒单、已成部分正常交收；市价单全不成整单拒单且账本不动。
- 全成订单（限价 GTC、IOC、市价 GTC、FOK，买卖两边）在首次返回、重启重放、重复提交三条路径下结论一致，且事件记录中不含与全成矛盾的撤销、拒单、挂单事件。
- FOK 全部成交、流动性不足原子拒单、幂等重复提交与重启后重复提交。
- 截止时间订单部分成交后到期、剩余预留释放、重启后不重复失效。
- 新旧事件记录混合重放，以及损坏记录严格失败。

`FillOutcomeBoundaryTest` 的日志按业务分组输出每个用例的成功/失败，并在类结束时输出 `[业务汇总:...] 用例数=.. 成功=.. 失败=..`，可直接看出各业务的用例数与结果。

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
