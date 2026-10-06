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
