package com.github.lechandonga.orderbookmatchingenginejava.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.model.RejectReason;
import com.github.lechandonga.orderbookmatchingenginejava.model.Side;
import com.github.lechandonga.orderbookmatchingenginejava.model.TimeInForce;

import java.math.BigDecimal;
import java.time.Instant;

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
            RejectReason rejectReason,
            TimeInForce timeInForce,
            Instant expiresAt,
            Long remainingQty
    ) {
        public static PayloadBuilder builder() {
            return new PayloadBuilder();
        }
    }

    public static class PayloadBuilder {
        private String clientOrderId;
        private String accountId;
        private String symbol;
        private Side side;
        private OrderType orderType;
        private BigDecimal price;
        private Long qty;
        private BigDecimal reservePrice;
        private Long filledQty;
        private Long tradeSeq;
        private String counterOrderId;
        private String counterAccountId;
        private Side takerSide;
        private RejectReason rejectReason;
        private TimeInForce timeInForce;
        private Instant expiresAt;
        private Long remainingQty;

        public PayloadBuilder clientOrderId(String value) { this.clientOrderId = value; return this; }
        public PayloadBuilder accountId(String value) { this.accountId = value; return this; }
        public PayloadBuilder symbol(String value) { this.symbol = value; return this; }
        public PayloadBuilder side(Side value) { this.side = value; return this; }
        public PayloadBuilder orderType(OrderType value) { this.orderType = value; return this; }
        public PayloadBuilder price(BigDecimal value) { this.price = value; return this; }
        public PayloadBuilder qty(Long value) { this.qty = value; return this; }
        public PayloadBuilder reservePrice(BigDecimal value) { this.reservePrice = value; return this; }
        public PayloadBuilder filledQty(Long value) { this.filledQty = value; return this; }
        public PayloadBuilder tradeSeq(Long value) { this.tradeSeq = value; return this; }
        public PayloadBuilder counterOrderId(String value) { this.counterOrderId = value; return this; }
        public PayloadBuilder counterAccountId(String value) { this.counterAccountId = value; return this; }
        public PayloadBuilder takerSide(Side value) { this.takerSide = value; return this; }
        public PayloadBuilder rejectReason(RejectReason value) { this.rejectReason = value; return this; }
        public PayloadBuilder timeInForce(TimeInForce value) { this.timeInForce = value; return this; }
        public PayloadBuilder expiresAt(Instant value) { this.expiresAt = value; return this; }
        public PayloadBuilder remainingQty(Long value) { this.remainingQty = value; return this; }

        public Payload build() {
            return new Payload(clientOrderId, accountId, symbol, side, orderType, price, qty,
                    reservePrice, filledQty, tradeSeq, counterOrderId, counterAccountId, takerSide,
                    rejectReason, timeInForce, expiresAt, remainingQty);
        }
    }
}
