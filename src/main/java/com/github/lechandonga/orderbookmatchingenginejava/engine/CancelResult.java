package com.github.lechandonga.orderbookmatchingenginejava.engine;

import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.domain.RejectReason;

public record CancelResult(
        String clientOrderId,
        boolean success,
        OrderStatus status,
        long canceledQuantity,
        RejectReason rejectReason,
        String rejectMessage,
        String cancelReason
) {
    static CancelResult failed(String clientOrderId, RejectReason reason) {
        return new CancelResult(clientOrderId, false, null, 0L, reason, reason.getDescription(), null);
    }
}
