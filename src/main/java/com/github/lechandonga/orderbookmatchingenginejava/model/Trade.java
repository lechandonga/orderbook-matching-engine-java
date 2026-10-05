package com.github.lechandonga.orderbookmatchingenginejava.model;

import java.math.BigDecimal;

public record Trade(
        long seq,
        String symbol,
        BigDecimal price,
        long qty,
        String takerOrderId,
        String takerAccount,
        Side takerSide,
        String makerOrderId,
        String makerAccount
) {
}
