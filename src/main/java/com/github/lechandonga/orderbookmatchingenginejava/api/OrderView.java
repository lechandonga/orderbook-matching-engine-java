package com.github.lechandonga.orderbookmatchingenginejava.api;

import com.github.lechandonga.orderbookmatchingenginejava.model.Order;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.model.Side;

import java.math.BigDecimal;

public record OrderView(
        String clientOrderId,
        String accountId,
        String symbol,
        Side side,
        OrderType type,
        BigDecimal price,
        long qty,
        long filledQty,
        long remainingQty,
        OrderStatus status
) {
    public static OrderView of(Order o) {
        return new OrderView(o.getClientOrderId(), o.getAccountId(), o.getSymbol(), o.getSide(),
                o.getType(), o.getPrice(), o.getQty(), o.getFilledQty(), o.remainingQty(), o.getStatus());
    }
}
