package com.github.lechandonga.orderbookmatchingenginejava.api;

import com.github.lechandonga.orderbookmatchingenginejava.model.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.model.RejectReason;
import com.github.lechandonga.orderbookmatchingenginejava.model.TimeInForce;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 提交/撤单的统一结果。幂等重放返回与首次完全一致的结果。
 */
public record OrderResult(
        String clientOrderId,
        OrderStatus status,
        long qty,
        long filledQty,
        long remainingQty,
        long tradedQtyThisCall,
        BigDecimal avgPrice,
        TimeInForce timeInForce,
        Instant expiresAt,
        boolean replay,
        RejectReason rejectReason,
        String rejectMessage
) {
}
