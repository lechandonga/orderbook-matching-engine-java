package com.github.lechandonga.orderbookmatchingenginejava.engine;

import com.github.lechandonga.orderbookmatchingenginejava.api.OrderResult;
import com.github.lechandonga.orderbookmatchingenginejava.api.SubmitOrderRequest;
import com.github.lechandonga.orderbookmatchingenginejava.config.MatchingProperties;
import com.github.lechandonga.orderbookmatchingenginejava.event.EventRecord;
import com.github.lechandonga.orderbookmatchingenginejava.event.EventStore;
import com.github.lechandonga.orderbookmatchingenginejava.event.EventType;
import com.github.lechandonga.orderbookmatchingenginejava.ledger.Account;
import com.github.lechandonga.orderbookmatchingenginejava.ledger.AccountRepository;
import com.github.lechandonga.orderbookmatchingenginejava.model.Order;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.model.RejectReason;
import com.github.lechandonga.orderbookmatchingenginejava.model.Side;
import com.github.lechandonga.orderbookmatchingenginejava.model.TimeInForce;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MatchingService {
    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);
    private static final long MAX_QTY = 1_000_000_000L;
    private static final BigDecimal MAX_PRICE = BigDecimal.valueOf(1_000_000);

    private final EventStore eventStore;
    private final AccountRepository accounts;
    private final MatchingProperties properties;
    private final Clock clock;
    private final Object lock = new Object();
    private final Map<String, Order> orders = new HashMap<>();
    private final Map<String, OrderBook> books = new HashMap<>();
    private long nextEventSeq = 1L;
    private long tradeSeq;

    @Autowired
    public MatchingService(EventStore eventStore, AccountRepository accounts, MatchingProperties properties) {
        this(eventStore, accounts, properties, Clock.systemUTC());
    }

    public MatchingService(EventStore eventStore, AccountRepository accounts,
                           MatchingProperties properties, Clock clock) {
        this.eventStore = eventStore;
        this.accounts = accounts;
        this.properties = properties;
        this.clock = clock;
    }

    @PostConstruct
    public void recover() {
        synchronized (lock) {
            eventStore.readAll().forEach(this::apply);
            expireDue(clock.instant());
            log.info("订单簿恢复完成：events={}, orders={}, trades={}", nextEventSeq - 1, orders.size(), tradeSeq);
        }
    }

    @Scheduled(fixedDelayString = "${matching.expiry-check-ms:100}")
    public void expireDueOrders() {
        expireDue(clock.instant());
    }

    public List<OrderResult> expireDue(Instant now) {
        synchronized (lock) {
            return orders.values().stream()
                    .filter(order -> order.getExpiresAt() != null)
                    .filter(order -> order.getStatus() == OrderStatus.NEW
                            || order.getStatus() == OrderStatus.PARTIALLY_FILLED)
                    .filter(order -> !order.getExpiresAt().isAfter(now))
                    .sorted(Comparator.comparingLong(Order::getSubmitSeq))
                    .map(this::expire)
                    .toList();
        }
    }

    public OrderResult submit(SubmitOrderRequest request) {
        synchronized (lock) {
            Instant now = clock.instant();
            Order existing = orders.get(request.getClientOrderId());
            if (existing != null) {
                if (!sameRequest(existing, request)) {
                    return rejectedView(existing.getClientOrderId(), RejectReason.IDEMPOTENCY_CONFLICT);
                }
                return result(existing, 0, true);
            }

            Validation validation = validate(request, now);
            if (validation.reason() != null) {
                Order order = rejectedOrder(request, validation, now);
                appendAndApply(terminalEvent(order, EventType.ORDER_REJECTED, validation.reason()));
                return result(order, 0, false);
            }

            Order order = validation.order();
            RejectReason riskReason = riskReason(order);
            if (riskReason != null) {
                appendAndApply(terminalEvent(order, EventType.ORDER_REJECTED, riskReason));
                return result(order, 0, false);
            }
            reserve(order);
            long available = book(order.getSymbol()).candidates(order, order.remainingQty()).stream()
                    .mapToLong(OrderBook.Match::qty).sum();
            if (order.getTimeInForce() == TimeInForce.FOK && available < order.remainingQty()) {
                releaseRemaining(order);
                order.setStatus(OrderStatus.REJECTED);
                order.setRejectReason(RejectReason.WOULD_NOT_FILL);
                appendAndApply(terminalEvent(order, EventType.ORDER_REJECTED, RejectReason.WOULD_NOT_FILL));
                return result(order, 0, false);
            }

            List<EventRecord> batch = new ArrayList<>();
            long baseSeq = nextEventSeq - 1;
            batch.add(event(EventType.ORDER_ACCEPTED, payload(order).build()));
            EventRecord accepted = batch.get(0);
            orders.put(order.getClientOrderId(), order);
            long traded = appendMatchEvents(order, batch);
            long unfilledQty = order.getQty() - order.getFilledQty() - traded;
            if (unfilledQty > 0) {
                long seq = baseSeq + batch.size() + 1;
                if (order.getTimeInForce() == TimeInForce.IOC) {
                    batch.add(seqEvent(seq, EventType.ORDER_CANCELLED,
                            payload(order).remainingQty(unfilledQty).build()));
                } else if (order.getType() == OrderType.MARKET && order.getTimeInForce() == TimeInForce.GTC) {
                    batch.add(seqEvent(seq, EventType.ORDER_REJECTED, payload(order)
                            .remainingQty(unfilledQty)
                            .rejectReason(RejectReason.NO_LIQUIDITY).build()));
                } else {
                    batch.add(seqEvent(seq, EventType.ORDER_ADDED_TO_BOOK,
                            payload(order).remainingQty(unfilledQty).build()));
                }
            }
            nextEventSeq = accepted.seq() + 1;
            eventStore.appendAll(batch);
            batch.forEach(this::apply);
            return result(order, traded, false);
        }
    }

    public OrderResult cancel(String clientOrderId, String accountId) {
        synchronized (lock) {
            Order order = orders.get(clientOrderId);
            if (order == null) {
                return rejectedView(clientOrderId, RejectReason.ORDER_NOT_FOUND);
            }
            if (!Objects.equals(order.getAccountId(), accountId)) {
                return rejectedView(clientOrderId, RejectReason.CROSS_ACCOUNT_CANCEL);
            }
            if (order.getStatus() != OrderStatus.NEW && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
                return result(order, 0, true);
            }
            EventRecord record = terminalEvent(order, EventType.ORDER_CANCELLED, null);
            appendAndApply(record);
            return result(order, 0, false);
        }
    }

    public Order order(String clientOrderId) {
        synchronized (lock) {
            return orders.get(clientOrderId);
        }
    }

    public Collection<Account> accounts() {
        return accounts.all();
    }

    private long appendMatchEvents(Order taker, List<EventRecord> batch) {
        long traded = 0;
        for (OrderBook.Match match : book(taker.getSymbol()).match(taker, taker.remainingQty())) {
            traded += match.qty();
            tradeSeq++;
            batch.add(event(EventType.ORDER_TRADED, payload(taker)
                    .qty(match.qty())
                    .price(match.price())
                    .filledQty(taker.getFilledQty() + traded)
                    .tradeSeq(tradeSeq)
                    .counterOrderId(match.maker().getClientOrderId())
                    .counterAccountId(match.maker().getAccountId())
                    .takerSide(taker.getSide())
                    .remainingQty(taker.getQty() - taker.getFilledQty() - traded)
                    .build()));
        }
        return traded;
    }

    private OrderResult expire(Order order) {
        appendAndApply(terminalEvent(order, EventType.ORDER_EXPIRED, null));
        return result(order, 0, false);
    }

    private void appendAndApply(EventRecord record) {
        eventStore.appendAll(List.of(record));
        apply(record);
    }

    private EventRecord terminalEvent(Order order, EventType type, RejectReason reason) {
        EventRecord.PayloadBuilder builder = payload(order).remainingQty(order.remainingQty());
        if (reason != null) {
            builder.rejectReason(reason);
        }
        return event(type, builder.build());
    }

    private EventRecord event(EventType type, EventRecord.Payload payload) {
        return new EventRecord(EventStore.FORMAT_VERSION, nextEventSeq++, type, payload, null);
    }

    private EventRecord seqEvent(long seq, EventType type, EventRecord.Payload payload) {
        return new EventRecord(EventStore.FORMAT_VERSION, seq, type, payload, null);
    }

    private EventRecord.PayloadBuilder payload(Order order) {
        return EventRecord.Payload.builder()
                .clientOrderId(order.getClientOrderId())
                .accountId(order.getAccountId())
                .symbol(order.getSymbol())
                .side(order.getSide())
                .orderType(order.getType())
                .price(order.getPrice())
                .qty(order.getQty())
                .reservePrice(order.getReservePrice())
                .filledQty(order.getFilledQty())
                .timeInForce(order.getTimeInForce())
                .expiresAt(order.getExpiresAt());
    }

    private OrderBook book(String symbol) {
        return books.computeIfAbsent(symbol, OrderBook::new);
    }

    private Account account(String accountId) {
        return accounts.find(accountId)
                .orElseThrow(() -> new IllegalStateException("账户不存在: " + accountId));
    }

    private void reserve(Order order) {
        Account account = account(order.getAccountId());
        if (order.getSide() == Side.BUY) {
            account.reserveCash(order.getReservePrice().multiply(BigDecimal.valueOf(order.getQty())));
        } else {
            account.reservePosition(order.getSymbol(), order.getQty());
        }
    }

    private void releaseRemaining(Order order) {
        if (order.remainingQty() <= 0) {
            return;
        }
        Account account = account(order.getAccountId());
        if (order.getSide() == Side.BUY) {
            account.releaseCash(order.getReservePrice().multiply(BigDecimal.valueOf(order.remainingQty())));
        } else {
            account.releasePosition(order.getSymbol(), order.remainingQty());
        }
    }

    private void apply(EventRecord record) {
        if (record.seq() > nextEventSeq || record.seq() < nextEventSeq - 1) {
            throw new IllegalStateException("运行时事件 seq 不连续: " + record.seq() + "，期望 " + nextEventSeq);
        }
        nextEventSeq = Math.max(nextEventSeq, record.seq() + 1);
        EventRecord.Payload p = record.payload();
        switch (record.type()) {
            case ORDER_ACCEPTED -> accept(p, !orders.containsKey(p.clientOrderId()));
            case ORDER_ADDED_TO_BOOK -> addToBook(requiredOrder(p));
            case ORDER_TRADED -> trade(p);
            case ORDER_CANCELLED -> terminate(requiredOrder(p), OrderStatus.CANCELLED, null);
            case ORDER_EXPIRED -> terminate(requiredOrder(p), OrderStatus.EXPIRED, null);
            case ORDER_REJECTED -> reject(p);
        }
    }

    private void accept(EventRecord.Payload p, boolean reserve) {
        if (orders.containsKey(p.clientOrderId())) {
            return;
        }
        Order order = new Order(p.clientOrderId(), p.accountId(), p.symbol(), p.side(), p.orderType(),
                p.price(), p.qty(), p.reservePrice(),
                p.timeInForce() == null ? TimeInForce.GTC : p.timeInForce(), p.expiresAt(), nextEventSeq - 1);
        orders.put(order.getClientOrderId(), order);
        if (reserve) {
            reserve(order);
        }
        order.setStatus(OrderStatus.NEW);
    }

    private void addToBook(Order order) {
        if (order.remainingQty() > 0) {
            book(order.getSymbol()).add(order);
        }
    }

    private void trade(EventRecord.Payload p) {
        Order taker = requiredOrder(p);
        Order maker = orders.get(p.counterOrderId());
        if (maker == null) {
            throw new IllegalStateException("成交事件缺少对手订单: " + p.counterOrderId());
        }
        long qty = p.qty();
        settle(taker, maker, qty, p.price());
        taker.addFill(qty, p.price());
        maker.addFill(qty, p.price());
        if (p.tradeSeq() != null) {
            tradeSeq = Math.max(tradeSeq, p.tradeSeq());
        }
        taker.setStatus(taker.remainingQty() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
        maker.setStatus(maker.remainingQty() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
        if (maker.remainingQty() == 0) {
            book(maker.getSymbol()).remove(maker);
        }
    }

    private void settle(Order taker, Order maker, long qty, BigDecimal price) {
        Account takerAccount = account(taker.getAccountId());
        Account makerAccount = account(maker.getAccountId());
        if (taker.getSide() == Side.BUY) {
            takerAccount.settleBuy(taker.getSymbol(), qty, price, taker.getReservePrice());
            makerAccount.settleSell(maker.getSymbol(), qty, price);
        } else {
            takerAccount.settleSell(taker.getSymbol(), qty, price);
            makerAccount.settleBuy(maker.getSymbol(), qty, price, maker.getReservePrice());
        }
    }

    private void terminate(Order order, OrderStatus status, RejectReason reason) {
        book(order.getSymbol()).remove(order);
        releaseRemaining(order);
        order.setStatus(status);
        order.setRejectReason(reason);
    }

    private void reject(EventRecord.Payload p) {
        Order order = orders.get(p.clientOrderId());
        boolean newOrder = order == null;
        if (order == null) {
            order = new Order(p.clientOrderId(), p.accountId(), p.symbol(), p.side(), p.orderType(),
                    p.price(), p.qty() == null ? 0 : p.qty(), p.reservePrice(),
                    p.timeInForce() == null ? TimeInForce.GTC : p.timeInForce(), p.expiresAt(), nextEventSeq - 1);
            orders.put(order.getClientOrderId(), order);
        }
        RejectReason reason = p.rejectReason() == null ? RejectReason.NO_LIQUIDITY : p.rejectReason();
        if (order.getStatus() != OrderStatus.REJECTED || order.getRejectReason() == null) {
            if (newOrder) {
                order.setStatus(OrderStatus.REJECTED);
                order.setRejectReason(reason);
            } else if (order.remainingQty() > 0) {
                terminate(order, OrderStatus.REJECTED, reason);
            } else {
                order.setStatus(OrderStatus.REJECTED);
                order.setRejectReason(reason);
            }
        } else {
            order.setRejectReason(reason);
        }
    }

    private Order requiredOrder(EventRecord.Payload p) {
        Order order = orders.get(p.clientOrderId());
        if (order == null) {
            throw new IllegalStateException("事件引用了不存在的订单: " + p.clientOrderId());
        }
        return order;
    }

    private Validation validate(SubmitOrderRequest request, Instant now) {
        if (isBlank(request.getClientOrderId()) || isBlank(request.getAccountId())
                || isBlank(request.getSymbol()) || request.getSide() == null
                || request.getType() == null || request.getQty() == null) {
            return Validation.invalid(RejectReason.FIELD_MISSING);
        }
        if (accounts.find(request.getAccountId()).isEmpty()) {
            return Validation.invalid(RejectReason.UNKNOWN_ACCOUNT);
        }
        if (!properties.getSymbols().contains(request.getSymbol())) {
            return Validation.invalid(RejectReason.UNKNOWN_SYMBOL);
        }
        long qty = request.getQty();
        if (qty < 1 || qty > MAX_QTY) {
            return Validation.invalid(RejectReason.INVALID_QTY);
        }
        BigDecimal price = request.getPrice();
        if (request.getType() == OrderType.LIMIT
                && (price == null || price.signum() <= 0 || price.compareTo(MAX_PRICE) > 0)) {
            return Validation.invalid(RejectReason.INVALID_PRICE);
        }
        if (request.getType() == OrderType.MARKET && price != null) {
            return Validation.invalid(RejectReason.MARKET_PRICE_FORBIDDEN);
        }
        TimeInForce tif = request.getTimeInForce() == null ? TimeInForce.GTC : request.getTimeInForce();
        BigDecimal reservePrice = price;
        if (request.getType() == OrderType.MARKET) {
            reservePrice = properties.getMarketProtectionPrice().get(request.getSymbol());
            if (reservePrice == null || reservePrice.signum() <= 0) {
                return Validation.invalid(RejectReason.MARKET_PROTECTION_PRICE_MISSING);
            }
        }
        if (request.getExpiresAt() != null
                && (tif == TimeInForce.IOC || tif == TimeInForce.FOK)) {
            return Validation.invalid(RejectReason.IMMEDIATE_OR_CANCELLED_FORBIDS_EXPIRY);
        }
        if (request.getExpiresAt() != null && !request.getExpiresAt().isAfter(now)) {
            return Validation.invalid(RejectReason.INVALID_EXPIRES_AT);
        }
        Order order = new Order(request.getClientOrderId(), request.getAccountId(), request.getSymbol(),
                request.getSide(), request.getType(), price, qty, reservePrice, tif,
                request.getExpiresAt(), nextEventSeq);
        return Validation.valid(order);
    }

    private boolean canReserve(Order order) {
        Account account = account(order.getAccountId());
        BigDecimal amount = order.getReservePrice().multiply(BigDecimal.valueOf(order.getQty()));
        if (order.getSide() == Side.BUY) {
            return account.getAvailableCash().compareTo(amount) >= 0
                    && account.getReservedCash().add(amount).compareTo(account.getExposureLimit()) <= 0;
        }
        return account.getAvailablePosition(order.getSymbol()) >= order.getQty();
    }

    private RejectReason riskReason(Order order) {
        if (!canReserve(order)) {
            if (order.getSide() == Side.BUY) {
                Account account = account(order.getAccountId());
                BigDecimal amount = order.getReservePrice().multiply(BigDecimal.valueOf(order.getQty()));
                if (account.getAvailableCash().compareTo(amount) < 0) {
                    return RejectReason.INSUFFICIENT_AVAILABLE_CASH;
                }
                return RejectReason.EXPOSURE_LIMIT_EXCEEDED;
            }
            return RejectReason.INSUFFICIENT_AVAILABLE_POSITION;
        }
        return null;
    }

    private Order rejectedOrder(SubmitOrderRequest request, Validation validation, Instant now) {
        BigDecimal price = request.getPrice();
        TimeInForce tif = request.getTimeInForce() == null ? TimeInForce.GTC : request.getTimeInForce();
        long qty = request.getQty() == null ? 0 : request.getQty();
        Order order = new Order(nullToEmpty(request.getClientOrderId()), nullToEmpty(request.getAccountId()),
                nullToEmpty(request.getSymbol()), request.getSide(), request.getType(), price, qty,
                price == null ? BigDecimal.ONE : price,
                tif, request.getExpiresAt(), nextEventSeq);
        order.setStatus(OrderStatus.REJECTED);
        order.setRejectReason(validation.reason());
        return order;
    }

    private boolean sameRequest(Order order, SubmitOrderRequest request) {
        return Objects.equals(order.getAccountId(), request.getAccountId())
                && Objects.equals(order.getSymbol(), request.getSymbol())
                && order.getSide() == request.getSide()
                && order.getType() == request.getType()
                && Objects.equals(order.getPrice(), request.getPrice())
                && order.getQty() == (request.getQty() == null ? 0 : request.getQty())
                && order.getTimeInForce() == (request.getTimeInForce() == null
                ? TimeInForce.GTC : request.getTimeInForce())
                && Objects.equals(order.getExpiresAt(), request.getExpiresAt());
    }

    private OrderResult result(Order order, long tradedThisCall, boolean replay) {
        BigDecimal avgPrice = order.getFilledQty() == 0 ? null
                : order.getFilledAmount().divide(BigDecimal.valueOf(order.getFilledQty()));
        return new OrderResult(order.getClientOrderId(), order.getStatus(), order.getQty(),
                order.getFilledQty(), order.remainingQty(), tradedThisCall, avgPrice,
                order.getTimeInForce(), order.getExpiresAt(), replay,
                order.getRejectReason(),
                order.getRejectReason() == null ? null : order.getRejectReason().getDescription());
    }

    private OrderResult rejectedView(String clientOrderId, RejectReason reason) {
        return new OrderResult(clientOrderId, OrderStatus.REJECTED, 0, 0, 0, 0, null,
                TimeInForce.GTC, null, true, reason, reason.getDescription());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Validation(Order order, RejectReason reason) {
        static Validation valid(Order order) {
            return new Validation(order, null);
        }

        static Validation invalid(RejectReason reason) {
            return new Validation(null, reason);
        }
    }
}
