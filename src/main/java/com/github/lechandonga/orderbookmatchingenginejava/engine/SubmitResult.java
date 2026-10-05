package com.github.lechandonga.orderbookmatchingenginejava.engine;

import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.domain.RejectReason;

import java.math.BigDecimal;
import java.util.List;

/**
 * 下单结果。HTTP 入口与内部入口返回完全相同的结构，拒绝时 rejectReason 非空。
 * idempotentReplay=true 表示该外部订单号此前已处理过，本次结果取自首次记录。
 */
public record SubmitResult(
        String clientOrderId,
        boolean accepted,
        OrderStatus status,
        long filledQuantity,
        long remainingQuantity,
        BigDecimal averageFillPrice,
        List<FillView> fills,
        RejectReason rejectReason,
        String rejectMessage,
        boolean idempotentReplay
) {
    static SubmitResult rejected(String clientOrderId, RejectReason reason, boolean replay) {
        return new SubmitResult(clientOrderId, false, OrderStatus.REJECTED, 0L, 0L,
                null, List.of(), reason, reason.getDescription(), replay);
    }

    public SubmitResult withFills(List<FillView> newFills) {
        return new SubmitResult(clientOrderId, accepted, status, filledQuantity,
                remainingQuantity, averageFillPrice, List.copyOf(newFills),
                rejectReason, rejectMessage, idempotentReplay);
    }
}
