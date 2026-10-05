package com.github.lechandonga.orderbookmatchingenginejava.order;

import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.domain.Side;

import java.math.BigDecimal;

/**
 * 活动订单（不可变值对象；状态变化通过替换实例实现）。
 *
 * @param seq 引擎分配的单调递增序号，用于同价时间优先
 */
public record Order(
        String clientOrderId,
        String accountId,
        String symbol,
        Side side,
        OrderType type,
        long price,
        long originalQty,
        long filledQty,
        OrderStatus status,
        long seq
) {
    public long remainingQty() {
        return originalQty - filledQty;
    }

    public boolean isActive() {
        return status == OrderStatus.NEW || status == OrderStatus.PARTIALLY_FILLED;
    }

    public Order withFill(long additionalFilled, OrderStatus newStatus) {
        return new Order(clientOrderId, accountId, symbol, side, type, price,
                originalQty, filledQty + additionalFilled, newStatus, seq);
    }

    public Order withStatus(OrderStatus newStatus) {
        return new Order(clientOrderId, accountId, symbol, side, type, price,
                originalQty, filledQty, newStatus, seq);
    }

    public BigDecimal displayPrice() {
        return BigDecimal.valueOf(price, 8);
    }
}
