package com.github.lechandonga.orderbookmatchingenginejava.tests;

import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.domain.RejectReason;
import com.github.lechandonga.orderbookmatchingenginejava.domain.Side;
import com.github.lechandonga.orderbookmatchingenginejava.engine.CancelResult;
import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lechandonga.orderbookmatchingenginejava.engine.SubmitResult;
import com.github.lechandonga.orderbookmatchingenginejava.order.CancelRequest;
import com.github.lechandonga.orderbookmatchingenginejava.order.OrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Path;

import static com.github.lechandonga.orderbookmatchingenginejava.tests.support.EngineTestSupport.AAPL;
import static com.github.lechandonga.orderbookmatchingenginejava.tests.support.EngineTestSupport.newEngine;
import static com.github.lechandonga.orderbookmatchingenginejava.tests.support.EngineTestSupport.tempLedger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务用例组 1：订单生命周期——全部成交、部分成交、撤单与状态流转。
 */
@DisplayName("业务-订单生命周期：部分成交 / 全部成交 / 撤单")
class MatchingLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(MatchingLifecycleTest.class);

    private Path ledger;
    private MatchingEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        ledger = tempLedger();
        engine = newEngine(ledger);
    }

    @Test
    @DisplayName("用例1.1 限价卖单先入簿，限价买单全部吃单 -> 全部成交，按卖一价结算")
    void fullMatchAtMakerPrice() {
        log.info(">>> [生命周期-全部成交] bob 卖 30@150，alice 买 30 限价 160");
        SubmitResult sell = engine.submit(limit("O-SELL-1", "bob", Side.SELL, "150", 30));
        assertEquals(OrderStatus.NEW, sell.status());

        SubmitResult buy = engine.submit(limit("O-BUY-1", "alice", Side.BUY, "160", 30));
        assertTrue(buy.accepted());
        assertEquals(OrderStatus.FILLED, buy.status());
        assertEquals(30, buy.filledQuantity());
        assertEquals(0, buy.remainingQuantity());
        assertEquals(1, buy.fills().size());
        assertEquals(0, new BigDecimal("150").compareTo(buy.fills().get(0).price()));
        assertEquals(0, new BigDecimal("150").compareTo(buy.averageFillPrice()));
        assertEquals(OrderStatus.FILLED, engine.getOrder("O-SELL-1").status());
        log.info("<<< 买单成交价 {}，双方状态 FILLED，符合价格优先（取卖一 150）",
                buy.averageFillPrice());
    }

    @Test
    @DisplayName("用例1.2 对手盘数量不足 -> taker 部分成交后残量留在簿中，maker 全部成交")
    void partialFillLeavesRemainderOnBook() {
        log.info(">>> [生命周期-部分成交] bob 卖 20@150；alice 买 50@150，应成交 20、余 30");
        engine.submit(limit("P-SELL", "bob", Side.SELL, "150", 20));
        SubmitResult buy = engine.submit(limit("P-BUY", "alice", Side.BUY, "150", 50));

        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.status());
        assertEquals(20, buy.filledQuantity());
        assertEquals(30, buy.remainingQuantity());
        assertEquals(1, engine.bookDepth(AAPL, Side.BUY));
        assertEquals(0, engine.bookDepth(AAPL, Side.SELL));

        SubmitResult moreSell = engine.submit(limit("P-SELL-2", "bob", Side.SELL, "150", 30));
        assertEquals(OrderStatus.FILLED, moreSell.status());
        assertEquals(OrderStatus.FILLED, engine.getOrder("P-BUY").status());
        assertEquals(50, engine.getOrder("P-BUY").filledQty());
        log.info("<<< 残量 30 被后续卖单成交，买单累计成交 50，时间优先的挂单正确补成");
    }

    @Test
    @DisplayName("用例1.3 活动单可撤销，撤销后剩余数量明确且簿中移除；终结单不可再撤")
    void cancelActiveOrderRejectsWhenInactive() {
        log.info(">>> [生命周期-撤单] carol 买 5@100 挂单后撤单，二次撤单应返回 ORDER_NOT_ACTIVE");
        SubmitResult queued = engine.submit(limit("C-1", "carol", Side.BUY, "100", 5));
        assertEquals(OrderStatus.NEW, queued.status());

        CancelResult cancel = engine.cancel(new CancelRequest("C-1", "carol"));
        assertTrue(cancel.success());
        assertEquals(OrderStatus.CANCELED, cancel.status());
        assertEquals(5, cancel.canceledQuantity());
        assertEquals(0, engine.bookDepth(AAPL, Side.BUY));
        log.info("<<< 首次撤单成功，释放买入预留现金，账户可用额度恢复");

        CancelResult again = engine.cancel(new CancelRequest("C-1", "carol"));
        assertFalse(again.success());
        assertEquals(RejectReason.ORDER_NOT_ACTIVE, again.rejectReason());

        CancelResult otherAccount = engine.cancel(new CancelRequest("C-1", "bob"));
        assertEquals(RejectReason.CANCEL_ACCOUNT_MISMATCH, otherAccount.rejectReason());
        CancelResult missing = engine.cancel(new CancelRequest("NOT-EXIST", "bob"));
        assertEquals(RejectReason.ORDER_NOT_FOUND, missing.rejectReason());
        log.info("<<< 重复撤单/他人撤单/不存在订单分别得到可区分原因");
    }

    @Test
    @DisplayName("用例1.4 市价单按 IOC：有盘口立即成交，残量撤销而非留在簿中")
    void marketOrderIocRemainder() {
        log.info(">>> [生命周期-市价单] bob 卖 10@200；alice 市价买 30，成交 10、余 20 撤销");
        engine.submit(limit("M-SELL", "bob", Side.SELL, "200", 10));
        SubmitResult market = engine.submit(
                new OrderRequest("M-BUY", "alice", AAPL, Side.BUY, OrderType.MARKET, null, 30L));
        assertEquals(OrderStatus.CANCELED, market.status());
        assertEquals(10, market.filledQuantity());
        assertEquals(20, market.remainingQuantity());
        assertEquals(0, engine.bookDepth(AAPL, Side.BUY));
        assertNotNull(market.fills());
        log.info("<<< 市价买单成交 10，IOC 撤余 20，未在买簿留单");
    }

    @Test
    @DisplayName("用例1.5 无对手盘的市价单拒绝（MARKET_NOT_TRADEABLE），订单状态 REJECTED")
    void marketOrderWithoutQuoteRejected() {
        log.info(">>> [生命周期-市价拒单] 空盘口下市价买入必须拒绝");
        SubmitResult result = engine.submit(
                new OrderRequest("M-EMPTY", "alice", AAPL, Side.BUY, OrderType.MARKET, null, 1L));
        assertFalse(result.accepted());
        assertEquals(RejectReason.MARKET_NOT_TRADEABLE, result.rejectReason());
        assertEquals(OrderStatus.REJECTED, result.status());
        assertNull(engine.getOrder("M-EMPTY"));
        log.info("<<< 市价单被拒，未生成活动订单、未改动账本");
    }

    private static OrderRequest limit(String id, String account, Side side,
                                      String price, long qty) {
        return new OrderRequest(id, account, AAPL, side, OrderType.LIMIT,
                new BigDecimal(price), qty);
    }
}
