package com.github.lechandonga.orderbookmatchingenginejava.engine;

import java.math.BigDecimal;

/**
 * 一笔成交的对外视图。
 */
public record FillView(
        long tradeId,
        String makerOrderId,
        long quantity,
        BigDecimal price,
        long notional
) {
}
