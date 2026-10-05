package com.github.lechandonga.orderbookmatchingenginejava.tests;

import com.github.lechandonga.orderbookmatchingenginejava.domain.Side;
import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lechandonga.orderbookmatchingenginejava.engine.SubmitResult;
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

/**
 * 业务用例组 5：撮合优先级——价格优先、同价时间优先。
 */
@DisplayName("业务-撮合优先级：价格优先，同价时间优先")
class PriorityRuleTest {

    private static final Logger log = LoggerFactory.getLogger(PriorityRuleTest.class);

    private MatchingEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        Path ledger = tempLedger();
        engine = newEngine(ledger);
    }

    @Test
    @DisplayName("用例5.1 多档卖单：买单优先与最低价成交，成交价取对手价")
    void pricePriorityPicksBestAsk() {
        log.info(">>> [优先级-价格] 卖盘 10@160、10@150、10@140（乱序提交）；市价买单 15 股")
                ;
        limit("PR-A1", "bob", Side.SELL, "160", 10);
        limit("PR-A2", "bob", Side.SELL, "150", 10);
        limit("PR-A3", "bob", Side.SELL, "140", 10);

        SubmitResult buy = engine.submit(
                new com.github.lechandonga.orderbookmatchingenginejava.order.OrderRequest(
                        "PR-TAKER", "alice", AAPL, Side.BUY,
                        com.github.lechandonga.orderbookmatchingenginejava.domain.OrderType.MARKET,
                        null, 15L));

        assertEquals(2, buy.fills().size());
        assertEquals(new BigDecimal("140"), buy.fills().get(0).price());
        assertEquals(10, buy.fills().get(0).quantity());
        assertEquals(new BigDecimal("150"), buy.fills().get(1).price());
        assertEquals(5, buy.fills().get(1).quantity());
        log.info("<<< 先吃 140 的 10 股、再吃 150 的 5 股，160 档未触碰");
    }

    @Test
    @DisplayName("用例5.2 同价位：先提交的卖单先成交（FIFO 时间优先）")
    void timePriorityWithinSamePrice() {
        log.info(">>> [优先级-时间] bob 先卖 5@150、carol 后卖 5@150；alice 买 5")
                ;
        limit("PR-T1", "bob", Side.SELL, "150", 5);
        limit("PR-T2", "alice", Side.SELL, "150", 5);

        SubmitResult buy = limit("PR-TT", "alice", Side.BUY, "150", 5);
        assertEquals("PR-T1", buy.fills().get(0).makerOrderId());
        assertEquals(com.github.lechandonga.orderbookmatchingenginejava.domain.OrderStatus.FILLED,
                engine.getOrder("PR-T1").status());
        assertEquals(com.github.lechandonga.orderbookmatchingenginejava.domain.OrderStatus.NEW,
                engine.getOrder("PR-T2").status());
        log.info("<<< 同价 150 上 bob 的早单先成交，carol 的晚单仍在簿中");
    }

    private com.github.lechandonga.orderbookmatchingenginejava.engine.SubmitResult limit(
            String id, String account, Side side, String price, long qty) {
        return engine.submit(new com.github.lechandonga.orderbookmatchingenginejava.order.OrderRequest(
                id, account, AAPL, side,
                com.github.lechandonga.orderbookmatchingenginejava.domain.OrderType.LIMIT,
                new BigDecimal(price), qty));
    }
}
