package com.github.lechandonga.orderbookmatchingenginejava.engine;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 账户账本快照（金额单位为展示货币，价格精度 1e-8）。
 */
public record AccountSnapshot(
        String accountId,
        BigDecimal cashTotal,
        BigDecimal cashReserved,
        BigDecimal cashAvailable,
        Map<String, Long> positions,
        Map<String, Long> reservedPositions
) {
}
