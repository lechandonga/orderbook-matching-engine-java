package com.github.lechandonga.orderbookmatchingenginejava.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 内存订单。状态完全由事件回放得到，禁止脱离事件直接修改。
 */
public class Order {
    private final String clientOrderId;
    private final String accountId;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    private final BigDecimal price;
    private final long qty;
    /** 市价单风控预留时使用的最差价格；限价单等于委托价格。 */
    private final BigDecimal reservePrice;
    private final TimeInForce timeInForce;
    private final Instant expiresAt;
    private final long submitSeq;

    private long filledQty;
    private BigDecimal filledAmount = BigDecimal.ZERO;
    private OrderStatus status;
    private RejectReason rejectReason;

    public Order(String clientOrderId, String accountId, String symbol, Side side, OrderType type,
                 BigDecimal price, long qty, BigDecimal reservePrice, TimeInForce timeInForce,
                 Instant expiresAt, long submitSeq) {
        this.clientOrderId = clientOrderId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.qty = qty;
        this.reservePrice = reservePrice;
        this.timeInForce = timeInForce == null ? TimeInForce.GTC : timeInForce;
        this.expiresAt = expiresAt;
        this.submitSeq = submitSeq;
        this.filledQty = 0;
        this.status = OrderStatus.NEW;
    }

    public long remainingQty() {
        return qty - filledQty;
    }

    public String getClientOrderId() { return clientOrderId; }
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getType() { return type; }
    public BigDecimal getPrice() { return price; }
    public long getQty() { return qty; }
    public BigDecimal getReservePrice() { return reservePrice; }
    public long getSubmitSeq() { return submitSeq; }
    public TimeInForce getTimeInForce() { return timeInForce; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getFilledQty() { return filledQty; }
    public void setFilledQty(long filledQty) { this.filledQty = filledQty; }
    public BigDecimal getFilledAmount() { return filledAmount; }
    public void addFill(long qty, BigDecimal price) {
        this.filledQty += qty;
        this.filledAmount = filledAmount.add(price.multiply(BigDecimal.valueOf(qty)));
    }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public RejectReason getRejectReason() { return rejectReason; }
    public void setRejectReason(RejectReason rejectReason) { this.rejectReason = rejectReason; }
}
