package com.github.lechandonga.orderbookmatchingenginejava.account;

import java.util.HashMap;
import java.util.Map;

/**
 * 账户账本。所有字段为内部定点整数（价格 1e-8，数量 1，金额 1e-8 计价单位）。
 *
 * cashTotal        现金总额（成交买入扣减 / 卖出增加）
 * cashReserved     已被活动买单预留的资金（风控只校验 cashTotal - cashReserved）
 * positions        每个标的的净持仓（股/份）
 * reservedPositions 每个标的被活动卖单预留的持仓
 * maxCash          单笔买入可用额度上限的校验基准（固定额度，便于测试）
 * maxExposure      单笔订单名义金额敞口上限
 *
 * 守恒关系（单个账户，忽略撤单释放，撤单只把预留转回可用）：
 *   买入成交：cashTotal -= 成交额，现金预留同步减少相同金额；持仓增加
 *   卖出成交：cashTotal += 成交额，持仓预留同步减少；持仓减少
 */
public class Account {
    private final String accountId;
    private long cashTotal;
    private long cashReserved;
    private final Map<String, Long> positions = new HashMap<>();
    private final Map<String, Long> reservedPositions = new HashMap<>();
    private final long maxCash;
    private final long maxExposure;

    public Account(String accountId, long cashTotal, long maxCash, long maxExposure) {
        this.accountId = accountId;
        this.cashTotal = cashTotal;
        this.maxCash = maxCash;
        this.maxExposure = maxExposure;
    }

    public String getAccountId() {
        return accountId;
    }

    public long getCashTotal() {
        return cashTotal;
    }

    public long getCashReserved() {
        return cashReserved;
    }

    public long availableCash() {
        return cashTotal - cashReserved;
    }

    public long getMaxCash() {
        return maxCash;
    }

    public long getMaxExposure() {
        return maxExposure;
    }

    public long getPosition(String symbol) {
        return positions.getOrDefault(symbol, 0L);
    }

    public long getReservedPosition(String symbol) {
        return reservedPositions.getOrDefault(symbol, 0L);
    }

    public long availablePosition(String symbol) {
        return getPosition(symbol) - getReservedPosition(symbol);
    }

    public Map<String, Long> snapshotPositions() {
        return Map.copyOf(positions);
    }

    // ---- 仅允许在引擎锁内调用的账本变更 ----

    public void reserveCash(long amount) {
        cashReserved += amount;
    }

    public void releaseCash(long amount) {
        if (cashReserved < amount) {
            throw new IllegalStateException("现金预留释放超额: account=" + accountId);
        }
        cashReserved -= amount;
    }

    public void settleBuy(long notional, long qty, String symbol) {
        if (cashReserved < notional) {
            throw new IllegalStateException("买单结算金额超过预留: account=" + accountId);
        }
        cashReserved -= notional;
        cashTotal -= notional;
        positions.merge(symbol, qty, Long::sum);
    }

    public void reservePosition(String symbol, long qty) {
        reservedPositions.merge(symbol, qty, Long::sum);
    }

    public void releasePosition(String symbol, long qty) {
        long current = getReservedPosition(symbol);
        if (current < qty) {
            throw new IllegalStateException("持仓预留释放超额: account=" + accountId + ", symbol=" + symbol);
        }
        reservedPositions.put(symbol, current - qty);
    }

    public void settleSell(long notional, long qty, String symbol) {
        long reserved = getReservedPosition(symbol);
        long held = getPosition(symbol);
        if (reserved < qty || held < qty) {
            throw new IllegalStateException("卖单结算超过预留/持仓: account=" + accountId + ", symbol=" + symbol);
        }
        reservedPositions.put(symbol, reserved - qty);
        positions.put(symbol, held - qty);
        cashTotal += notional;
    }

    /** 重放账户初始化事件使用。 */
    public void initFromReplay(long initialCash) {
        this.cashTotal = initialCash;
        this.cashReserved = 0;
        this.positions.clear();
        this.reservedPositions.clear();
    }

    public void addPositionOnReplay(String symbol, long qty) {
        positions.merge(symbol, qty, Long::sum);
    }
}
