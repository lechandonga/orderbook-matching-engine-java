package com.github.lechandonga.orderbookmatchingenginejava.book;

import com.github.lechandonga.orderbookmatchingenginejava.domain.Side;
import com.github.lechandonga.orderbookmatchingenginejava.order.Order;

import java.util.LinkedList;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 单个标的的订单簿（调用方持引擎锁，内部无需额外同步）。
 *
 * 撮合优先级：价格优先（买单取最高买价、卖单取最低卖价），
 * 同价时间优先（引擎分配的 seq 小者排在档位队首）。
 */
public class OrderBook {

    private final String symbol;
    private final NavigableMap<Long, LinkedList<Order>> bids = new TreeMap<>();
    private final NavigableMap<Long, LinkedList<Order>> asks = new TreeMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    /** 对手盘最优报价；无报价或价格不穿越 taker 限价时为空。 */
    public Optional<Long> bestCrossingQuote(Side takerSide, long limitPrice) {
        Match match = peek(takerSide, limitPrice);
        return match == null ? Optional.empty() : Optional.of(match.price());
    }

    /** 查看下一个可成交对手单（不改动簿状态）。 */
    public Match peek(Side takerSide, long limitPrice) {
        NavigableMap<Long, LinkedList<Order>> opposite = opposite(takerSide);
        while (!opposite.isEmpty()) {
            Map.Entry<Long, LinkedList<Order>> level =
                    takerSide == Side.BUY ? opposite.firstEntry() : opposite.lastEntry();
            if (level.getValue().isEmpty()) {
                opposite.remove(level.getKey());
                continue;
            }
            long price = level.getKey();
            if (limitPrice > 0 && !crosses(takerSide, price, limitPrice)) {
                return null;
            }
            return new Match(level.getValue().getFirst(), price);
        }
        return null;
    }

    /**
     * 对手单成交后的簿更新：全部成交则出队；部分成交则用更新后的订单替换队首，
     * 同价时间优先位置保持不变。
     */
    public void onMakerFilled(Match match, Order updatedMaker) {
        LinkedList<Order> level = sideOf(match.resting().side()).get(match.price());
        if (level == null || level.isEmpty()) {
            throw new IllegalStateException("档位在成交后丢失: price=" + match.price());
        }
        level.removeFirst();
        if (updatedMaker.isActive()) {
            level.addFirst(updatedMaker);
        } else if (level.isEmpty()) {
            opposite(match.resting().side()).remove(match.price());
        }
    }

    /** 限价单按价格入簿；同价追加到队尾（时间优先）。 */
    public void add(Order order) {
        sideOf(order.side()).computeIfAbsent(order.price(), price -> new LinkedList<>()).addLast(order);
    }

    /** 从簿中删除活动单（撤单）；返回是否找到并删除。 */
    public boolean remove(Order order) {
        NavigableMap<Long, LinkedList<Order>> side = sideOf(order.side());
        LinkedList<Order> level = side.get(order.price());
        if (level == null) {
            return false;
        }
        boolean removed = level.removeIf(existing ->
                existing.clientOrderId().equals(order.clientOrderId()));
        if (level.isEmpty()) {
            side.remove(order.price());
        }
        return removed;
    }

    public int depth(Side side) {
        return sideOf(side).values().stream().mapToInt(LinkedList::size).sum();
    }

    /**
     * 无副作用地估算吃掉 qty 数量所需的最坏成交额（市价单风控预留用）。
     * 按价格优先逐档累计；盘口数量不足时，缺口按最后见到的一档价格估算
     * （盘口完全为空时返回 0，由调用方按 MARKET_NOT_TRADEABLE 拒绝）。
     */
    public long worstNotional(Side takerSide, long qty) {
        NavigableMap<Long, LinkedList<Order>> opposite = opposite(takerSide);
        Iterable<Map.Entry<Long, LinkedList<Order>>> levels =
                takerSide == Side.BUY ? opposite.entrySet() : opposite.descendingMap().entrySet();
        long remaining = qty;
        long notional = 0L;
        long lastPrice = 0L;
        for (Map.Entry<Long, LinkedList<Order>> level : levels) {
            if (remaining <= 0) {
                break;
            }
            lastPrice = level.getKey();
            for (Order order : level.getValue()) {
                long available = order.remainingQty();
                long take = Math.min(remaining, available);
                notional += take * lastPrice;
                remaining -= take;
                if (remaining <= 0) {
                    break;
                }
            }
        }
        // 市价单为 IOC：盘口数量以外无法成交，不预留缺口部分，从源头杜绝超额释放。
        return notional;
    }

    private NavigableMap<Long, LinkedList<Order>> opposite(Side takerSide) {
        return takerSide == Side.BUY ? asks : bids;
    }

    private NavigableMap<Long, LinkedList<Order>> sideOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private boolean crosses(Side takerSide, long restingPrice, long limitPrice) {
        return takerSide == Side.BUY ? restingPrice <= limitPrice : restingPrice >= limitPrice;
    }

    /** 一次撮合候选：队首对手单与成交价。 */
    public record Match(Order resting, long price) {
    }
}
