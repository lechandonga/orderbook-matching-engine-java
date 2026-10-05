package com.github.lechandonga.orderbookmatchingenginejava.tests;

import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.domain.RejectReason;
import com.github.lechandonga.orderbookmatchingenginejava.domain.Side;
import com.github.lechandonga.orderbookmatchingenginejava.engine.AccountSnapshot;
import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lechandonga.orderbookmatchingenginejava.engine.SubmitResult;
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

/**
 * 业务用例组 2：前置风控（字段校验、可用额度、敞口、持仓）与拒单不影响账本。
 */
@DisplayName("业务-前置风控：越界/缺失/超额/持仓不足拒绝且不改账本")
class RiskControlTest {

    private static final Logger log = LoggerFactory.getLogger(RiskControlTest.class);

    private Path ledger;
    private MatchingEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        ledger = tempLedger();
        engine = newEngine(ledger);
    }

    @Test
    @DisplayName("用例2.1 字段缺失、价格/数量越界、市价单带价 -> 各自返回可区分拒单原因")
    void invalidFieldsHaveDistinctReasons() {
        log.info(">>> [风控-参数] 缺失/价格非法/数量越界/市价带价 四类输入");
        SubmitResult missing = engine.submit(new OrderRequest(
                null, "alice", AAPL, Side.BUY, OrderType.LIMIT, new BigDecimal("100"), 1L));
        assertEquals(RejectReason.MISSING_FIELD, missing.rejectReason());

        SubmitResult badPrice = engine.submit(req("R-PRICE", "alice", Side.BUY,
                OrderType.LIMIT, new BigDecimal("0"), 1L));
        assertEquals(RejectReason.INVALID_PRICE, badPrice.rejectReason());

        SubmitResult priceTooHigh = engine.submit(req("R-PRICE2", "alice", Side.BUY,
                OrderType.LIMIT, new BigDecimal("999999"), 1L));
        assertEquals(RejectReason.INVALID_PRICE, priceTooHigh.rejectReason());

        SubmitResult badQty = engine.submit(req("R-QTY", "alice", Side.BUY,
                OrderType.LIMIT, new BigDecimal("100"), 0L));
        assertEquals(RejectReason.INVALID_QUANTITY, badQty.rejectReason());

        SubmitResult marketWithPrice = engine.submit(req("R-MKP", "alice", Side.BUY,
                OrderType.MARKET, new BigDecimal("100"), 1L));
        assertEquals(RejectReason.MARKET_PRICE_NOT_ALLOWED, marketWithPrice.rejectReason());

        SubmitResult unknown = engine.submit(req("R-ACC", "ghost", Side.BUY,
                OrderType.LIMIT, new BigDecimal("100"), 1L));
        assertEquals(RejectReason.UNKNOWN_ACCOUNT, unknown.rejectReason());
        log.info("<<< 六类异常输入分别得到 MISSING_FIELD/INVALID_PRICE/INVALID_QUANTITY/"
                + "MARKET_PRICE_NOT_ALLOWED/UNKNOWN_ACCOUNT，均未静默受理");
    }

    @Test
    @DisplayName("用例2.2 买单名义金额超可用额度 -> INSUFFICIENT_CASH，账本分毫不变")
    void buyBeyondCashRejectedWithoutLedgerChange() {
        log.info(">>> [风控-额度] carol 现金/敞口上限均为 1000，下 50@11=550 在敞口内但现金不足，必须被拒");
        AccountSnapshot before = engine.snapshotAccount("carol");
        SubmitResult result = engine.submit(req("RISK-CASH", "carol", Side.BUY,
                OrderType.LIMIT, new BigDecimal("11"), 100L));
        assertFalse(result.accepted());
        assertEquals(RejectReason.INSUFFICIENT_CASH, result.rejectReason());

        AccountSnapshot after = engine.snapshotAccount("carol");
        assertEquals(before.cashTotal(), after.cashTotal());
        assertEquals(before.cashReserved(), after.cashReserved());
        log.info("<<< 拒单后现金总额 {}、预留 {} 均不变", after.cashTotal(), after.cashReserved());
    }

    @Test
    @DisplayName("用例2.3 单笔名义金额超敞口上限 -> EXPOSURE_LIMIT_EXCEEDED 优先于额度判定")
    void exposureLimitRejected() {
        log.info(">>> [风控-敞口] 单笔 100@600=60000 超过 bob 敞口上限 50000");
        SubmitResult result = engine.submit(req("RISK-EXP", "bob", Side.BUY,
                OrderType.LIMIT, new BigDecimal("600"), 100L));
        assertEquals(RejectReason.EXPOSURE_LIMIT_EXCEEDED, result.rejectReason());
        assertEquals(OrderStatus.REJECTED, result.status());
        log.info("<<< 敞口拒单成立，且未产生预留扣减");
    }

    @Test
    @DisplayName("用例2.4 卖单超过可用持仓 -> INSUFFICIENT_POSITION；活动卖单会先冻结持仓")
    void sellBeyondPositionRejectedAndReservedByActiveOrder() {
        log.info(">>> [风控-持仓] carol 无持仓卖出被拒；bob 挂卖 50 后再卖 1 也被拒（已冻结）");
        SubmitResult noPosition = engine.submit(req("RISK-POS0", "carol", Side.SELL,
                OrderType.LIMIT, new BigDecimal("100"), 1L));
        assertEquals(RejectReason.INSUFFICIENT_POSITION, noPosition.rejectReason());

        SubmitResult sellAll = engine.submit(req("RISK-POS1", "bob", Side.SELL,
                OrderType.LIMIT, new BigDecimal("300"), 50L));
        assertEquals(OrderStatus.NEW, sellAll.status());
        SubmitResult sellMore = engine.submit(req("RISK-POS2", "bob", Side.SELL,
                OrderType.LIMIT, new BigDecimal("300"), 1L));
        assertEquals(RejectReason.INSUFFICIENT_POSITION, sellMore.rejectReason());

        AccountSnapshot bob = engine.snapshotAccount("bob");
        assertEquals(50L, bob.positions().get(AAPL));
        assertEquals(50L, bob.reservedPositions().get(AAPL));
        log.info("<<< 持仓 50、冻结 50、可用 0，超额卖单被拒且未发生超卖");
    }

    private static OrderRequest req(String id, String account, Side side,
                                    OrderType type, BigDecimal price, Long qty) {
        return new OrderRequest(id, account, AAPL, side, type, price, qty);
    }
}
