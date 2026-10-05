package com.github.lechandonga.orderbookmatchingenginejava.engine;

import com.github.lechandonga.orderbookmatchingenginejava.account.Account;
import com.github.lechandonga.orderbookmatchingenginejava.book.OrderBook;
import com.github.lechandonga.orderbookmatchingenginejava.config.MatchingProperties;
import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.domain.RejectReason;
import com.github.lechandonga.orderbookmatchingenginejava.domain.Side;
import com.github.lechandonga.orderbookmatchingenginejava.event.EventLog;
import com.github.lechandonga.orderbookmatchingenginejava.event.EventRecord;
import com.github.lechandonga.orderbookmatchingenginejava.event.EventType;
import com.github.lechandonga.orderbookmatchingenginejava.event.LedgerCorruptedException;
import com.github.lechandonga.orderbookmatchingenginejava.order.CancelRequest;
import com.github.lechandonga.orderbookmatchingenginejava.order.Order;
import com.github.lechandonga.orderbookmatchingenginejava.order.OrderRequest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存撮合 + 前置风控核心引擎。
 *
 * 一致性模型：所有公开方法在单一私有锁内串行执行（无超卖 / 重复成交 / 丢单）；
 * 每次状态迁移先追加事件日志再更新内存，重启后 {@link #restore()} 逐笔重放，
 * 订单状态与账本结果与宕机前逐笔一致。
 */
@Component
public class MatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);
    private static final int SCALE = 8;

    private final EventLog eventLog;
    private final MatchingProperties properties;
    private final Object lock = new Object();

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, OrderBook> books = new HashMap<>();
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final Map<String, RejectReason> rejectedOrders = new HashMap<>();
    private final Map<String, Long> cashReservations = new HashMap<>();
    /** 每单累计成交金额（加权平均价用），重放时同样维护。 */
    private final Map<String, Long> fillWeightedTotals = new HashMap<>();
    private long orderSeq;
    private long tradeSeq;
    private boolean restored;

    public MatchingEngine(EventLog eventLog, MatchingProperties properties) {
        this.eventLog = eventLog;
        this.properties = properties;
    }

    @PostConstruct
    public void restore() {
        synchronized (lock) {
            if (restored) {
                return;
            }
            List<EventRecord> records = eventLog.replay();
            if (records.isEmpty()) {
                seedAccounts();
            } else {
                for (EventRecord record : records) {
                    applyReplayed(record);
                }
            }
            rebuildBooks();
            restored = true;
            log.info("引擎恢复完成: 账户数={}, 订单数={}, 拒绝单数={}, 成交笔数={}",
                    accounts.size(), orders.size(), rejectedOrders.size(), tradeSeq);
        }
    }

    public SubmitResult submit(OrderRequest request) {
        synchronized (lock) {
            if (request != null && request.clientOrderId() != null) {
                Order existing = orders.get(request.clientOrderId());
                if (existing != null) {
                    if (checkIdempotentConflict(request, existing) != null) {
                        return SubmitResult.rejected(request.clientOrderId(),
                                RejectReason.DUPLICATE_ORDER_ID, true);
                    }
                    return resultFromOrder(existing, true);
                }
                RejectReason previousReject = rejectedOrders.get(request.clientOrderId());
                if (previousReject != null) {
                    return SubmitResult.rejected(request.clientOrderId(), previousReject, true);
                }
            }

            RejectReason invalid = validate(request);
            if (invalid != null) {
                return rejectAndRecord(request, invalid, false);
            }

            long qty = request.quantity();
            long price = request.type() == OrderType.LIMIT ? toScaledLong(request.price()) : 0L;
            Account account = accounts.get(request.accountId());
            OrderBook book = books.computeIfAbsent(request.symbol(), OrderBook::new);

            RejectReason riskReject = checkRisk(request, account, book, price, qty);
            if (riskReject != null) {
                return rejectAndRecord(request, riskReject, false);
            }

            long reserveNotional = 0L;
            if (request.side() == Side.BUY) {
                reserveNotional = request.type() == OrderType.LIMIT
                        ? multiplyChecked(price, qty)
                        : book.worstNotional(Side.BUY, qty);
                account.reserveCash(reserveNotional);
                cashReservations.put(request.clientOrderId(), reserveNotional);
            } else {
                account.reservePosition(request.symbol(), qty);
            }

            Order accepted = new Order(request.clientOrderId(), request.accountId(),
                    request.symbol(), request.side(), request.type(), price, qty, 0L,
                    OrderStatus.NEW, ++orderSeq);
            append(EventType.ORDER_ACCEPTED, acceptedPayload(accepted, reserveNotional));
            orders.put(accepted.clientOrderId(), accepted);
            log.info("订单受理: id={} account={} {} {} price={} qty={} 预留={}",
                    accepted.clientOrderId(), accepted.accountId(), accepted.side(),
                    accepted.type(),
                    accepted.type() == OrderType.LIMIT ? request.price() : "MKT",
                    qty, toDisplay(reserveNotional));

            MatchRun run = match(accepted, book);
            Order finalOrder = run.taker;
            if (finalOrder.remainingQty() > 0 && finalOrder.type() == OrderType.MARKET) {
                finalOrder = cancelInternal(finalOrder, "MARKET_IOC_REMAINDER");
            } else if (finalOrder.remainingQty() > 0) {
                Order queued = finalOrder.withStatus(
                        finalOrder.filledQty() > 0
                                ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW);
                orders.put(queued.clientOrderId(), queued);
                book.add(queued);
                finalOrder = queued;
            } else if (finalOrder.type() == OrderType.MARKET && finalOrder.side() == Side.BUY) {
                releaseMarketReserveRemainder(finalOrder);
            }
            return resultFromOrder(finalOrder, false).withFills(run.fills);
        }
    }

    public CancelResult cancel(CancelRequest request) {
        synchronized (lock) {
            if (request == null || isBlank(request.clientOrderId())
                    || isBlank(request.accountId())) {
                String id = request == null ? null : request.clientOrderId();
                return CancelResult.failed(id, RejectReason.MISSING_FIELD);
            }
            Order order = orders.get(request.clientOrderId());
            if (order == null) {
                return CancelResult.failed(request.clientOrderId(),
                        rejectedOrders.containsKey(request.clientOrderId())
                                ? RejectReason.ORDER_NOT_ACTIVE : RejectReason.ORDER_NOT_FOUND);
            }
            if (!order.accountId().equals(request.accountId())) {
                return CancelResult.failed(request.clientOrderId(),
                        RejectReason.CANCEL_ACCOUNT_MISMATCH);
            }
            if (!order.isActive()) {
                return CancelResult.failed(request.clientOrderId(), RejectReason.ORDER_NOT_ACTIVE);
            }
            Order canceled = cancelInternal(order, "USER_CANCEL");
            return new CancelResult(canceled.clientOrderId(), true, canceled.status(),
                    canceled.remainingQty(), null, null, "USER_CANCEL");
        }
    }

    public Order getOrder(String clientOrderId) {
        synchronized (lock) {
            return orders.get(clientOrderId);
        }
    }

    public AccountSnapshot snapshotAccount(String accountId) {
        synchronized (lock) {
            Account account = accounts.get(accountId);
            if (account == null) {
                return null;
            }
            Map<String, Long> positions = new LinkedHashMap<>(account.snapshotPositions());
            Map<String, Long> reserved = new LinkedHashMap<>();
            for (String symbol : positions.keySet()) {
                reserved.put(symbol, account.getReservedPosition(symbol));
            }
            return new AccountSnapshot(accountId, toDisplay(account.getCashTotal()),
                    toDisplay(account.getCashReserved()), toDisplay(account.availableCash()),
                    positions, reserved);
        }
    }

    public int bookDepth(String symbol, Side side) {
        synchronized (lock) {
            OrderBook book = books.get(symbol);
            return book == null ? 0 : book.depth(side);
        }
    }

    public long lastEventSeq() {
        synchronized (lock) {
            return eventLog.lastSeq();
        }
    }

    // ===================== 校验 / 幂等 / 风控 =====================

    private RejectReason validate(OrderRequest request) {
        if (request == null
                || isBlank(request.clientOrderId())
                || isBlank(request.accountId())
                || isBlank(request.symbol())
                || request.side() == null || request.type() == null
                || request.quantity() == null) {
            return RejectReason.MISSING_FIELD;
        }
        long qty = request.quantity();
        if (qty < properties.getMinOrderQuantity() || qty > properties.getMaxOrderQuantity()) {
            return RejectReason.INVALID_QUANTITY;
        }
        if (request.type() == null) {
            return RejectReason.MISSING_FIELD;
        }
        if (request.type() == OrderType.LIMIT) {
            if (request.price() == null || request.price().signum() <= 0
                    || request.price().compareTo(properties.getMaxPrice()) > 0
                    || request.price().stripTrailingZeros().scale() > SCALE
                    || toScaledLongSafe(request.price()) <= 0) {
                return RejectReason.INVALID_PRICE;
            }
        } else if (request.price() != null) {
            return RejectReason.MARKET_PRICE_NOT_ALLOWED;
        }
        return null;
    }

    private RejectReason checkIdempotentConflict(OrderRequest request, Order existing) {
        if (!request.accountId().equals(existing.accountId())
                || !request.symbol().equals(existing.symbol())
                || request.side() != existing.side()
                || request.type() != existing.type()
                || request.quantity() == null
                || request.quantity() != existing.originalQty()) {
            return RejectReason.DUPLICATE_ORDER_ID;
        }
        if (existing.type() == OrderType.LIMIT
                && (request.price() == null || toScaledLong(request.price()) != existing.price())) {
            return RejectReason.DUPLICATE_ORDER_ID;
        }
        return null;
    }

    private RejectReason checkRisk(OrderRequest request, Account account, OrderBook book,
                                   long price, long qty) {
        if (account == null) {
            return RejectReason.UNKNOWN_ACCOUNT;
        }
        if (request.side() == Side.BUY) {
            long worstNotional;
            if (request.type() == OrderType.LIMIT) {
                worstNotional = multiplyChecked(price, qty);
            } else {
                if (book.bestCrossingQuote(Side.BUY, 0L).isEmpty()) {
                    return RejectReason.MARKET_NOT_TRADEABLE;
                }
                worstNotional = book.worstNotional(Side.BUY, qty);
            }
            if (worstNotional > account.getMaxExposure()) {
                return RejectReason.EXPOSURE_LIMIT_EXCEEDED;
            }
            if (worstNotional > account.availableCash()) {
                return RejectReason.INSUFFICIENT_CASH;
            }
        } else {
            if (request.type() == OrderType.MARKET
                    && book.bestCrossingQuote(Side.SELL, 0L).isEmpty()) {
                return RejectReason.MARKET_NOT_TRADEABLE;
            }
            long referencePrice = request.type() == OrderType.LIMIT
                    ? price
                    : book.bestCrossingQuote(Side.SELL, 0L).orElseThrow();
            long notional = multiplyChecked(referencePrice, qty);
            if (notional > account.getMaxExposure()) {
                return RejectReason.EXPOSURE_LIMIT_EXCEEDED;
            }
            if (account.availablePosition(request.symbol()) < qty) {
                return RejectReason.INSUFFICIENT_POSITION;
            }
        }
        return null;
    }

    // ===================== 撮合 / 撤单 / 结算 =====================

    private MatchRun match(Order takerIn, OrderBook book) {
        MatchRun run = new MatchRun(takerIn);
        long limit = takerIn.type() == OrderType.LIMIT ? takerIn.price() : 0L;
        while (run.taker.remainingQty() > 0) {
            OrderBook.Match candidate = book.peek(run.taker.side(), limit);
            if (candidate == null) {
                break;
            }
            Order maker = candidate.resting();
            long tradeQty = Math.min(run.taker.remainingQty(), maker.remainingQty());
            long tradePrice = candidate.price();
            long notional = multiplyChecked(tradePrice, tradeQty);
            long tradeId = ++tradeSeq;

            Account takerAccount = accounts.get(run.taker.accountId());
            Account makerAccount = accounts.get(maker.accountId());
            if (run.taker.side() == Side.BUY) {
                takerAccount.settleBuy(notional, tradeQty, run.taker.symbol());
                releasePriceImprovement(run.taker, tradeQty, notional);
                makerAccount.settleSell(notional, tradeQty, maker.symbol());
            } else {
                takerAccount.settleSell(notional, tradeQty, run.taker.symbol());
                makerAccount.settleBuy(notional, tradeQty, maker.symbol());
                releasePriceImprovement(maker, tradeQty, notional);
            }

            Order takerAfter = run.taker.withFill(tradeQty,
                    run.taker.filledQty() + tradeQty == run.taker.originalQty()
                            ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
            Order makerAfter = maker.withFill(tradeQty,
                    maker.filledQty() + tradeQty == maker.originalQty()
                            ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
            book.onMakerFilled(candidate, makerAfter);
            orders.put(makerAfter.clientOrderId(), makerAfter);
            orders.put(takerAfter.clientOrderId(), takerAfter);
            fillWeightedTotals.merge(takerAfter.clientOrderId(), notional, Long::sum);
            fillWeightedTotals.merge(makerAfter.clientOrderId(), notional, Long::sum);

            append(EventType.TRADE,
                    tradePayload(tradeId, takerAfter, makerAfter, tradeQty, tradePrice, notional));
            run.fills.add(new FillView(tradeId, maker.clientOrderId(), tradeQty,
                    toDisplay(tradePrice), notional));
            log.info("成交: tradeId={} taker={} maker={} qty={} price={} notional={}",
                    tradeId, takerAfter.clientOrderId(), makerAfter.clientOrderId(),
                    tradeQty, toDisplay(tradePrice), toDisplay(notional));
            run.taker = takerAfter;
        }
        return run;
    }

    /**
     * 限价买单按限价预留现金，成交价不劣于限价，成交后释放
     * “限价×成交量 − 实际成交额”的价格改善部分，剩余预留恒等于 限价×剩余数量。
     * 市价买单的预留保留到全部成交或 IOC 撤余时统一处理。
     */
    private void releasePriceImprovement(Order buyOrder, long tradeQty, long actualNotional) {
        Long reserved = cashReservations.get(buyOrder.clientOrderId());
        if (reserved == null) {
            return;
        }
        long improvement;
        long remaining;
        if (buyOrder.type() == OrderType.LIMIT) {
            long expectedAtLimit = multiplyChecked(buyOrder.price(), tradeQty);
            improvement = expectedAtLimit - actualNotional;
            if (improvement <= 0) {
                return;
            }
            accounts.get(buyOrder.accountId()).releaseCash(improvement);
            remaining = reserved - improvement;
        } else {
            // 市价单：settleBuy 已按实际成交额扣减账户预留，这里只同步映射表
            remaining = reserved - actualNotional;
        }
        if (remaining == 0) {
            cashReservations.remove(buyOrder.clientOrderId());
        } else {
            cashReservations.put(buyOrder.clientOrderId(), remaining);
        }
    }

    private Order cancelInternal(Order order, String reason) {
        OrderBook book = books.get(order.symbol());
        if (book != null) {
            book.remove(order);
        }
        Order canceled = order.withStatus(OrderStatus.CANCELED);
        orders.put(canceled.clientOrderId(), canceled);
        long remainingQty = order.remainingQty();
        long releasedNotional = 0L;
        if (order.side() == Side.BUY) {
            // 限价单：预留恒等于 限价×剩余数量（成交时的价格改善已逐笔释放）；
            // 市价单：预留即盘口可见最坏成交额，IOC 撤余对应未成交部分。
            long expected = order.type() == OrderType.LIMIT
                    ? multiplyChecked(order.price(), remainingQty)
                    : cashReservations.getOrDefault(order.clientOrderId(), 0L);
            Long reserved = cashReservations.remove(order.clientOrderId());
            releasedNotional = expected;
            if (releasedNotional > 0) {
                accounts.get(order.accountId()).releaseCash(releasedNotional);
            }
        } else {
            accounts.get(order.accountId()).releasePosition(order.symbol(), remainingQty);
        }
        append(EventType.ORDER_CANCELED, cancelPayload(canceled, reason,
                remainingQty, releasedNotional));
        log.info("撤单: id={} reason={} 剩余数量={} 释放预留={}",
                canceled.clientOrderId(), reason, remainingQty, toDisplay(releasedNotional));
        return canceled;
    }

    private void releaseMarketReserveRemainder(Order filledMarketBuy) {
        Long reserved = cashReservations.remove(filledMarketBuy.clientOrderId());
        if (reserved != null && reserved > 0) {
            accounts.get(filledMarketBuy.accountId()).releaseCash(reserved);
        }
    }

    private SubmitResult rejectAndRecord(OrderRequest request, RejectReason reason, boolean replay) {
        String id = request == null ? null : request.clientOrderId();
        if (!isBlank(id)) {
            rejectedOrders.put(id, reason);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientOrderId", id);
        payload.put("accountId", request == null ? null : request.accountId());
        payload.put("symbol", request == null ? null : request.symbol());
        payload.put("side", request == null || request.side() == null ? null : request.side().name());
        payload.put("type", request == null || request.type() == null ? null : request.type().name());
        payload.put("quantity", request == null || request.quantity() == null ? null : request.quantity());
        payload.put("reason", reason.name());
        append(EventType.ORDER_REJECTED, payload);
        log.warn("拒单: id={} reason={} ({})", id, reason.name(), reason.getDescription());
        return SubmitResult.rejected(id, reason, replay);
    }

    private SubmitResult resultFromOrder(Order order, boolean replay) {
        long filled = order.filledQty();
        BigDecimal avg = averagePrice(order, filled);
        return new SubmitResult(order.clientOrderId(), order.status() != OrderStatus.REJECTED,
                order.status(), filled, order.remainingQty(), avg, new ArrayList<>(),
                null, null, replay);
    }

    private BigDecimal averagePrice(Order order, long filledQty) {
        if (filledQty == 0) {
            return null;
        }
        long weighted = fillWeightedTotals.getOrDefault(order.clientOrderId(), 0L);
        return toDisplay(weighted / filledQty);
    }

    private static final class MatchRun {
        private Order taker;
        private final List<FillView> fills = new ArrayList<>();

        private MatchRun(Order taker) {
            this.taker = taker;
        }
    }

    // ===================== 事件负载 =====================

    private EventRecord append(EventType type, Map<String, Object> payload) {
        return eventLog.append(type, payload);
    }

    private Map<String, Object> acceptedPayload(Order order, long reserveNotional) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientOrderId", order.clientOrderId());
        payload.put("accountId", order.accountId());
        payload.put("symbol", order.symbol());
        payload.put("side", order.side().name());
        payload.put("type", order.type().name());
        payload.put("price", order.price());
        payload.put("quantity", order.originalQty());
        payload.put("seq", order.seq());
        payload.put("reserveNotional", reserveNotional);
        return payload;
    }

    private Map<String, Object> tradePayload(long tradeId, Order taker, Order maker,
                                             long qty, long price, long notional) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tradeId", tradeId);
        payload.put("takerOrderId", taker.clientOrderId());
        payload.put("makerOrderId", maker.clientOrderId());
        payload.put("takerAccountId", taker.accountId());
        payload.put("makerAccountId", maker.accountId());
        payload.put("symbol", taker.symbol());
        payload.put("takerSide", taker.side().name());
        payload.put("quantity", qty);
        payload.put("price", price);
        payload.put("notional", notional);
        return payload;
    }

    private Map<String, Object> cancelPayload(Order order, String reason,
                                              long remainingQty, long releasedNotional) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientOrderId", order.clientOrderId());
        payload.put("accountId", order.accountId());
        payload.put("reason", reason);
        payload.put("filledQuantity", order.filledQty());
        payload.put("canceledQuantity", remainingQty);
        payload.put("releasedNotional", releasedNotional);
        return payload;
    }

    // ===================== 重放恢复 =====================

    private void applyReplayed(EventRecord record) {
        Map<String, Object> payload = record.payload();
        switch (record.type()) {
            case ACCOUNT_OPENED -> replayAccountOpened(payload);
            case ORDER_ACCEPTED -> replayOrderAccepted(payload);
            case TRADE -> replayTrade(payload);
            case ORDER_CANCELED -> replayCanceled(payload);
            case ORDER_REJECTED -> replayRejected(payload);
            default -> throw new LedgerCorruptedException("未知事件类型: " + record.type());
        }
    }

    private void replayAccountOpened(Map<String, Object> payload) {
        String accountId = requireString(payload, "accountId");
        long initialCash = requireLong(payload, "initialCash");
        long maxCash = requireLong(payload, "maxCash");
        long maxExposure = requireLong(payload, "maxExposure");
        Account account = new Account(accountId, initialCash, maxCash, maxExposure);
        Object positions = payload.get("positions");
        if (positions instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                account.addPositionOnReplay(String.valueOf(entry.getKey()),
                        ((Number) entry.getValue()).longValue());
            }
        }
        accounts.put(accountId, account);
    }

    private void replayOrderAccepted(Map<String, Object> payload) {
        Order order = new Order(
                requireString(payload, "clientOrderId"),
                requireString(payload, "accountId"),
                requireString(payload, "symbol"),
                Side.valueOf(requireString(payload, "side")),
                OrderType.valueOf(requireString(payload, "type")),
                requireLong(payload, "price"),
                requireLong(payload, "quantity"),
                0L, OrderStatus.NEW, requireLong(payload, "seq"));
        orders.put(order.clientOrderId(), order);
        orderSeq = Math.max(orderSeq, order.seq());
        long reserveNotional = requireLong(payload, "reserveNotional");
        if (order.side() == Side.BUY && reserveNotional > 0) {
            accounts.get(order.accountId()).reserveCash(reserveNotional);
            cashReservations.put(order.clientOrderId(), reserveNotional);
        } else if (order.side() == Side.SELL) {
            accounts.get(order.accountId()).reservePosition(order.symbol(), order.originalQty());
        }
    }

    private void replayTrade(Map<String, Object> payload) {
        long tradeId = requireLong(payload, "tradeId");
        tradeSeq = Math.max(tradeSeq, tradeId);
        String takerId = requireString(payload, "takerOrderId");
        String makerId = requireString(payload, "makerOrderId");
        long qty = requireLong(payload, "quantity");
        long price = requireLong(payload, "price");
        long notional = requireLong(payload, "notional");
        Side takerSide = Side.valueOf(requireString(payload, "takerSide"));

        Order taker = orders.get(takerId);
        Order maker = orders.get(makerId);
        if (taker == null || maker == null) {
            throw new LedgerCorruptedException("TRADE 引用了不存在的订单: " + tradeId);
        }
        Account takerAccount = accounts.get(taker.accountId());
        Account makerAccount = accounts.get(maker.accountId());
        if (takerSide == Side.BUY) {
            takerAccount.settleBuy(notional, qty, taker.symbol());
            consumeReservation(takerId, notional);
            makerAccount.settleSell(notional, qty, maker.symbol());
        } else {
            takerAccount.settleSell(notional, qty, taker.symbol());
            makerAccount.settleBuy(notional, qty, maker.symbol());
            consumeReservation(makerId, notional);
        }
        orders.put(takerId, taker.withFill(qty,
                taker.filledQty() + qty == taker.originalQty()
                        ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED));
        orders.put(makerId, maker.withFill(qty,
                maker.filledQty() + qty == maker.originalQty()
                        ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED));
        fillWeightedTotals.merge(takerId, notional, Long::sum);
        fillWeightedTotals.merge(makerId, notional, Long::sum);
    }

    private void consumeReservation(String buyOrderId, long consumedNotional) {
        Long reserved = cashReservations.get(buyOrderId);
        if (reserved == null || reserved < consumedNotional) {
            throw new LedgerCorruptedException(
                    "重放时买单预留与成交不一致: " + buyOrderId);
        }
        long remaining = reserved - consumedNotional;
        if (remaining == 0) {
            cashReservations.remove(buyOrderId);
        } else {
            cashReservations.put(buyOrderId, remaining);
        }
    }

    private void replayCanceled(Map<String, Object> payload) {
        String id = requireString(payload, "clientOrderId");
        Order order = orders.get(id);
        if (order == null) {
            throw new LedgerCorruptedException("ORDER_CANCELED 引用了不存在的订单: " + id);
        }
        long canceledQty = requireLong(payload, "canceledQuantity");
        if (canceledQty != order.remainingQty()) {
            throw new LedgerCorruptedException(
                    "撤单数量与订单剩余数量不一致: " + id);
        }
        long releasedNotional = requireLong(payload, "releasedNotional");
        if (order.side() == Side.BUY) {
            if (releasedNotional > 0) {
                accounts.get(order.accountId()).releaseCash(releasedNotional);
            }
            cashReservations.remove(id);
        } else {
            accounts.get(order.accountId()).releasePosition(order.symbol(), canceledQty);
        }
        orders.put(id, order.withStatus(OrderStatus.CANCELED));
    }

    private void replayRejected(Map<String, Object> payload) {
        String id = requireString(payload, "clientOrderId");
        rejectedOrders.put(id, RejectReason.valueOf(requireString(payload, "reason")));
    }

    /** 重放后按 seq 顺序把活动单放回订单簿，恢复价格优先 / 同价时间优先。 */
    private void rebuildBooks() {
        books.clear();
        orders.values().stream()
                .filter(Order::isActive)
                .sorted(Comparator.comparingLong(Order::seq))
                .forEach(order ->
                        books.computeIfAbsent(order.symbol(), OrderBook::new).add(order));
    }

    private void seedAccounts() {
        properties.getSeedAccounts().forEach((id, seed) -> {
            long initialCash = toScaledLong(seed.getInitialCash());
            long maxCash = toScaledLong(seed.getMaxCash());
            long maxExposure = toScaledLong(seed.getMaxExposure());
            Account account = new Account(id, initialCash, maxCash, maxExposure);
            seed.getPositions().forEach(account::addPositionOnReplay);
            accounts.put(id, account);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("accountId", id);
            payload.put("initialCash", initialCash);
            payload.put("maxCash", maxCash);
            payload.put("maxExposure", maxExposure);
            payload.put("positions", new LinkedHashMap<>(seed.getPositions()));
            append(EventType.ACCOUNT_OPENED, payload);
            log.info("播种账户: id={} 初始现金={} 敞口上限={}", id,
                    seed.getInitialCash(), seed.getMaxExposure());
        });
    }

    // ===================== 工具 =====================

    private static String requireString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new LedgerCorruptedException("事件缺少必需字符串字段: " + key);
        }
        return string;
    }

    private static long requireLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Number number)) {
            throw new LedgerCorruptedException("事件缺少必需整数字段: " + key);
        }
        return number.longValue();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static long toScaledLong(BigDecimal value) {
        return value.movePointRight(SCALE).longValueExact();
    }

    private static long toScaledLongSafe(BigDecimal value) {
        try {
            return value.movePointRight(SCALE).longValueExact();
        } catch (ArithmeticException e) {
            return -1L;
        }
    }

    static BigDecimal toDisplay(long scaled) {
        BigDecimal value = BigDecimal.valueOf(scaled, SCALE);
        BigDecimal stripped = value.stripTrailingZeros();
        // 显式设置 scale 避免 0/大数被 toPlainString 之外的场景格式化成科学计数法
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static long multiplyChecked(long a, long b) {
        long result = a * b;
        if (a != 0 && result / a != b) {
            throw new ArithmeticException("成交额溢出");
        }
        return result;
    }
}
