package com.github.lechandonga.orderbookmatchingenginejava.engine;

import com.github.lechandonga.orderbookmatchingenginejava.model.Order;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.model.Side;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

class OrderBook {
    private final String symbol;
    private final NavigableMap<BigDecimal, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<BigDecimal, Deque<Order>> asks = new TreeMap<>(Comparator.naturalOrder());

    OrderBook(String symbol) {
        this.symbol = symbol;
    }

    record Match(Order maker, long qty, BigDecimal price) {
    }

    String symbol() {
        return symbol;
    }

    void add(Order order) {
        ownLevels(order.getSide())
                .computeIfAbsent(order.getPrice(), ignored -> new ArrayDeque<>())
                .addLast(order);
    }

    void remove(Order order) {
        Map<BigDecimal, Deque<Order>> levels = ownLevels(order.getSide());
        Deque<Order> queue = levels.get(order.getPrice());
        if (queue != null) {
            queue.remove(order);
            if (queue.isEmpty()) {
                levels.remove(order.getPrice());
            }
        }
    }

    Optional<Order> best(Side takerSide) {
        return levels(takerSide).values().stream()
                .filter(queue -> !queue.isEmpty())
                .map(Deque::getFirst)
                .findFirst();
    }

    long matchableQty(Order taker) {
        long total = 0;
        for (Match candidate : candidates(taker, taker.remainingQty())) {
            total += candidate.qty();
        }
        return total;
    }

    List<Match> match(Order taker, long limitQty) {
        List<Match> matches = new ArrayList<>();
        while (matches.stream().mapToLong(Match::qty).sum() < limitQty) {
            long alreadyMatched = matches.stream().mapToLong(Match::qty).sum();
            Optional<Order> optionalMaker = bestCrossingMaker(taker);
            if (optionalMaker.isEmpty()) {
                break;
            }
            Order maker = optionalMaker.get();
            long qty = Math.min(maker.remainingQty(), limitQty - alreadyMatched);
            matches.add(new Match(maker, qty, maker.getPrice()));
            if (maker.remainingQty() == qty) {
                remove(maker);
            }
        }
        return matches;
    }

    List<Match> candidates(Order taker, long limitQty) {
        List<Match> preview = new ArrayList<>();
        long need = limitQty;
        for (Deque<Order> queue : levels(taker.getSide()).values()) {
            for (Order maker : List.copyOf(queue)) {
                if (need <= 0 || !crosses(taker, maker)) {
                    return preview;
                }
                long qty = Math.min(maker.remainingQty(), need);
                preview.add(new Match(maker, qty, maker.getPrice()));
                need -= qty;
            }
        }
        return preview;
    }

    private Optional<Order> bestCrossingMaker(Order taker) {
        Optional<Order> optionalMaker = best(taker.getSide());
        if (optionalMaker.isEmpty()) {
            return Optional.empty();
        }
        Order maker = optionalMaker.get();
        return crosses(taker, maker) ? Optional.of(maker) : Optional.empty();
    }

    private boolean crosses(Order taker, Order maker) {
        return taker.getType() == OrderType.MARKET || (taker.getSide() == Side.BUY
                ? taker.getPrice().compareTo(maker.getPrice()) >= 0
                : taker.getPrice().compareTo(maker.getPrice()) <= 0);
    }

    private NavigableMap<BigDecimal, Deque<Order>> levels(Side side) {
        return side == Side.BUY ? asks : bids;
    }

    private NavigableMap<BigDecimal, Deque<Order>> ownLevels(Side side) {
        return side == Side.BUY ? bids : asks;
    }
}
