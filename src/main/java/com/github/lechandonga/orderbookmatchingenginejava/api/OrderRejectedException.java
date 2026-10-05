package com.github.lechandonga.orderbookmatchingenginejava.api;

import com.github.lechandonga.orderbookmatchingenginejava.domain.RejectReason;

/**
 * 业务拒单异常：携带与内部入口完全一致的 {@link RejectReason}。
 */
public class OrderRejectedException extends RuntimeException {
    private final RejectReason reason;

    public OrderRejectedException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
