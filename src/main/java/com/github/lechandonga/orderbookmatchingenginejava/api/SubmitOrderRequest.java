package com.github.lechandonga.orderbookmatchingenginejava.api;

import com.github.lechandonga.orderbookmatchingenginejava.model.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.model.Side;
import com.github.lechandonga.orderbookmatchingenginejava.model.TimeInForce;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 新单请求。所有字段允许缺失，由 {@code OrderValidator} 统一给出可区分的拒单原因。
 */
public class SubmitOrderRequest {
    private String clientOrderId;
    private String accountId;
    private String symbol;
    private Side side;
    private OrderType type;
    private BigDecimal price;
    private Long qty;
    private TimeInForce timeInForce;
    private Instant expiresAt;

    public String getClientOrderId() { return clientOrderId; }
    public void setClientOrderId(String clientOrderId) { this.clientOrderId = clientOrderId; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public Side getSide() { return side; }
    public void setSide(Side side) { this.side = side; }
    public OrderType getType() { return type; }
    public void setType(OrderType type) { this.type = type; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Long getQty() { return qty; }
    public void setQty(Long qty) { this.qty = qty; }
    public TimeInForce getTimeInForce() { return timeInForce; }
    public void setTimeInForce(TimeInForce timeInForce) { this.timeInForce = timeInForce; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
