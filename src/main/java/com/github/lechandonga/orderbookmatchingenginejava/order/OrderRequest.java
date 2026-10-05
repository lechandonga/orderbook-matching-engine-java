package com.github.lechandonga.orderbookmatchingenginejava.order;

import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.domain.Side;

import java.math.BigDecimal;

/**
 * 下单请求（HTTP 入口与内部调用入口共用的同一 DTO，保证两入口判定一致）。
 *
 * @param clientOrderId 外部订单号，全局唯一幂等键
 * @param accountId     账户 ID
 * @param symbol        标的代码
 * @param side          BUY / SELL
 * @param type          LIMIT / MARKET
 * @param price         限价单价格（必须为正）；市价单必须为空
 * @param quantity      数量（正整数股/份）
 */
public record OrderRequest(
        String clientOrderId,
        String accountId,
        String symbol,
        Side side,
        OrderType type,
        BigDecimal price,
        Long quantity
) {
}
