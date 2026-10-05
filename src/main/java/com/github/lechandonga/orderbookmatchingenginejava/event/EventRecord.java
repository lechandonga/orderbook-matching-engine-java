package com.github.lechandonga.orderbookmatchingenginejava.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.model.RejectReason;
import com.github.lechandonga.orderbookmatchingenginejava.model.Side;

import java.math.BigDecimal;

/**
 * 事件记录格式（JSON Lines，每行一条）：
 * <pre>
 * {"version":1,"seq":12,"type":"ORDER_TRADED","payload":{...},"crc":"&lt;payload 的 CRC32 十六进制&gt;"}
 * </pre>
 * 兼容要求：version 仅允许 1；未知事件类型 / 字段缺失 / seq 不连续 / crc 不匹配一律报错，禁止静默跳过。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventRecord(
        int version,
        long seq,
        EventType type,
        Payload payload,
        String crc
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Payload(
            String clientOrderId,
            String accountId,
            String symbol,
            Side side,
            OrderType orderType,
            BigDecimal price,
            Long qty,
            BigDecimal reservePrice,
            Long filledQty,
            Long tradeSeq,
            String counterOrderId,
            String counterAccountId,
            Side takerSide,
            RejectReason rejectReason
    ) {
    }
}
