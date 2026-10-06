package com.github.lechandonga.orderbookmatchingenginejava.ledger;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 账户账本。所有变动只能由事件回放产生。
 * 不变量：cash >= 0；position(symbol) >= 0；reserved* 不超过对应余额。
 */
public class Account {
    private final String accountId;
    private BigDecimal cash;
    private final Map<String, Long> positions = new HashMap<>();
    private BigDecimal reservedCash = BigDecimal.ZERO;
    private final Map<String, Long> reservedPositions = new HashMap<>();
    private BigDecimal exposureLimit;

    public Account(String accountId, BigDecimal cash, BigDecimal exposureLimit) {
        this.accountId = accountId;
 this.cash = cash;
        this.exposureLimit = exposureLimit;
    }

    public String getAccountId() { return accountId; }
    public synchronized BigDecimal getCash() { return cash; }
    public synchronized BigDecimal getAvailableCash() { return cash.subtract(reservedCash); }
    public synchronized BigDecimal getReservedCash() { return reservedCash; }
    public synchronized BigDecimal getExposureLimit() { return exposureLimit; }

    public synchronized long getPosition(String symbol) {
        return positions.getOrDefault(symbol, 0L);
    }

    public synchronized long getAvailablePosition(String symbol) {
        return getPosition(symbol) - reservedPositions.getOrDefault(symbol, 0L);
    }

    public synchronized long getReservedPosition(String symbol) {
        return reservedPositions.getOrDefault(symbol, 0L);
    }

    /** 测试/种子初始化用：直接设置持仓。 */
    public synchronized void seedPosition(String symbol, long qty) {
        positions.put(symbol, qty);
    }

    /** 买单受理：按预留价冻结资金。 */
    public synchronized void reserveCash(BigDecimal amount) {
        if (getAvailableCash().compareTo(amount) < 0) {
            throw new IllegalStateException("可用资金不足，不应进入冻结: " + accountId);
        }
        if (reservedCash.add(amount).compareTo(exposureLimit) > 0) {
            throw new IllegalStateException("买入敞口超限，不应进入冻结: " + accountId);
        }
        reservedCash = reservedCash.add(amount);
    }

    /** 卖单受理：冻结持仓。 */
    public synchronized void reservePosition(String symbol, long qty) {
        if (getAvailablePosition(symbol) < qty) {
            throw new IllegalStateException("可用持仓不足，不应进入冻结: " + accountId);
        }
        reservedPositions.merge(symbol, qty, Long::sum);
    }

    /** 买单成交 qty：先释放预留资金，再按实际成交价扣款。 */
    public synchronized void settleBuy(String symbol, long qty, BigDecimal price, BigDecimal reservePrice) {
        BigDecimal reserved = reservePrice.multiply(BigDecimal.valueOf(qty));
        reservedCash = reservedCash.subtract(reserved);
        BigDecimal cost = price.multiply(BigDecimal.valueOf(qty));
        cash = cash.subtract(cost);
        positions.merge(symbol, qty, Long::sum);
        if (reservedCash.signum() < 0 || cash.signum() < 0) {
            throw new IllegalStateException("买单结算破坏账本不变量: " + accountId);
        }
    }

    /** 卖单成交 qty：先释放预留持仓，再交收并收款。 */
    public synchronized void settleSell(String symbol, long qty, BigDecimal price) {
        releasePosition(symbol, qty);
        long nextPosition = getPosition(symbol) - qty;
        if (nextPosition < 0) {
            throw new IllegalStateException("卖单结算后持仓为负: " + accountId);
        }
        positions.put(symbol, nextPosition);
        cash = cash.add(price.multiply(BigDecimal.valueOf(qty)));
    }

    /** 撤单/终结：释放买单剩余预留资金。 */
    public synchronized void releaseCash(BigDecimal amount) {
        reservedCash = reservedCash.subtract(amount);
        if (reservedCash.signum() < 0) {
            throw new IllegalStateException("释放资金超额: " + accountId);
        }
    }

    /** 撤单：释放卖单剩余预留持仓。 */
    public synchronized void releasePosition(String symbol, long qty) {
        long next = reservedPositions.getOrDefault(symbol, 0L) - qty;
        if (next < 0) {
            throw new IllegalStateException("释放持仓超额: " + accountId);
        }
        reservedPositions.put(symbol, next);
    }
}
