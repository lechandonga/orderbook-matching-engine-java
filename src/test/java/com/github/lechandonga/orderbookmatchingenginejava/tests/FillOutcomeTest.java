package com.github.lechandonga.orderbookmatchingenginejava.tests;

import com.github.lechandonga.orderbookmatchingenginejava.api.OrderResult;
import com.github.lechandonga.orderbookmatchingenginejava.config.MatchingProperties;
import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingService;
import com.github.lechandonga.orderbookmatchingenginejava.ledger.Account;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.model.RejectReason;
import com.github.lechandonga.orderbookmatchingenginejava.model.Side;
import com.github.lechandonga.orderbookmatchingenginejava.model.TimeInForce;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住“全成 / 部分成 / 全不成”的结论、账本释放、事件记录与重启/幂等一致性。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FillOutcomeTest {
    private static final Logger log = LoggerFactory.getLogger(FillOutcomeTest.class);

    private record BusinessStat(String business, int cases, int passed) {
    }

    private final Map<String, BusinessStat> stats = new LinkedHashMap<>();

    @TempDir
    Path tempDirectory;

    private MatchingService fresh(String file) {
        MatchingService service = TestSupport.service(tempDirectory.resolve(file),
                TestSupport.properties(), TestSupport.T0);
        service.recover();
        return service;
    }

    private MatchingService restart(String file) {
        return TestSupport.recovered(tempDirectory.resolve(file),
                TestSupport.properties(), TestSupport.T0.plusSeconds(10));
    }

    private long count(String file, String eventType) throws IOException {
        Pattern pattern = Pattern.compile("\"type\":\"" + eventType + "\"");
        return pattern.matcher(Files.readString(tempDirectory.resolve(file))).results().count();
    }

    private void assertFilled(OrderResult result, long qty) {
        assertEquals(OrderStatus.FILLED, result.status());
        assertEquals(qty, result.filledQty());
        assertEquals(0, result.remainingQty());
        assertNull(result.rejectReason());
        assertNull(result.rejectMessage());
    }

    private void assertNoReservedBuy(Account buyer, String expectedCash, long expectedPosition) {
        assertEquals(BigDecimal.ZERO, buyer.getReservedCash());
        assertEquals(new BigDecimal(expectedCash), buyer.getCash());
        assertEquals(expectedPosition, buyer.getPosition("BTCUSD"));
    }

    private void logBusiness(String business, OrderResult result) {
        BusinessStat previous = stats.getOrDefault(business, new BusinessStat(business, 0, 0));
        stats.put(business, new BusinessStat(business, previous.cases() + 1, previous.passed() + 1));
        log.info("[业务:{}] 用例数=1 成功=1 失败=0 结果 status={}, filled={}, remaining={}, reason={}",
                business, result.status(), result.filledQty(), result.remainingQty(), result.rejectReason());
    }

    @AfterAll
    void summarize() {
        int total = stats.values().stream().mapToInt(BusinessStat::cases).sum();
        int passed = stats.values().stream().mapToInt(BusinessStat::passed).sum();
        stats.values().forEach(stat -> log.info("[业务汇总:{}] 用例数={} 成功={} 失败={}",
                stat.business(), stat.cases(), stat.passed(), stat.cases() - stat.passed()));
        log.info("[业务汇总:全部] 覆盖业务数={} 用例总数={} 成功={} 失败={}",
                stats.size(), total, passed, total - passed);
    }

    @Test
    void iocLimitBuyFullyFilledIsFilledEverywhere() throws IOException {
        String file = "ioc-full-buy.jsonl";
        MatchingService service = fresh(file);
        service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 6));

        OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.LIMIT, "100", 6, TimeInForce.IOC, null));

        assertFilled(result, 6);
        assertNoReservedBuy(TestSupport.account(service, "buyer"), "99400", 6);
        OrderResult duplicate = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.LIMIT, "100", 6, TimeInForce.IOC, null));
        assertFilled(duplicate, 6);
        assertTrue(duplicate.replay());

        MatchingService recovered = restart(file);
        OrderResult afterRestart = recovered.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.LIMIT, "100", 6, TimeInForce.IOC, null));
        assertFilled(afterRestart, 6);
        assertTrue(afterRestart.replay());
        assertEquals(OrderStatus.FILLED, recovered.order("taker").getStatus());
        assertNoReservedBuy(TestSupport.account(recovered, "buyer"), "99400", 6);
        assertEquals(0, count(file, "ORDER_CANCELLED"));
        assertEquals(0, count(file, "ORDER_REJECTED"));
        assertEquals(1, count(file, "ORDER_ADDED_TO_BOOK"));
        logBusiness("IOC 限价买单全成-首次/重放/重启一致", result);
    }

    @Test
    void iocLimitSellFullyFilledIsFilledWithoutCancel() throws IOException {
        String file = "ioc-full-sell.jsonl";
        MatchingService service = fresh(file);
        service.submit(TestSupport.order("maker", "buyer", Side.BUY, OrderType.LIMIT, "100", 5));

        OrderResult result = service.submit(TestSupport.order("taker", "seller", Side.SELL,
                OrderType.LIMIT, "100", 5, TimeInForce.IOC, null));

        assertFilled(result, 5);
        Account seller = TestSupport.account(service, "seller");
        assertEquals(0, seller.getReservedPosition("BTCUSD"));
        assertEquals(95, seller.getPosition("BTCUSD"));
        assertEquals(new BigDecimal("100500"), seller.getCash());
        assertEquals(0, count(file, "ORDER_CANCELLED"));
        assertEquals(0, count(file, "ORDER_REJECTED"));
        assertEquals(1, count(file, "ORDER_ADDED_TO_BOOK"));

        MatchingService recovered = restart(file);
        assertEquals(OrderStatus.FILLED, recovered.order("taker").getStatus());
        assertEquals(0, TestSupport.account(recovered, "seller").getReservedPosition("BTCUSD"));
        logBusiness("IOC 限价卖单全成-无撤销事件", result);
    }

    @Test
    void iocPartiallyFilledCancelsOnlyRemainder() throws IOException {
        String file = "ioc-partial.jsonl";
        MatchingService service = fresh(file);
        service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 4));

        OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.LIMIT, "100", 10, TimeInForce.IOC, null));

        assertEquals(OrderStatus.CANCELLED, result.status());
        assertNull(result.rejectReason());
        assertEquals(4, result.filledQty());
        assertEquals(6, result.remainingQty());
        assertNoReservedBuy(TestSupport.account(service, "buyer"), "99600", 4);
        assertEquals(1, count(file, "ORDER_CANCELLED"));

        MatchingService recovered = restart(file);
        assertEquals(OrderStatus.CANCELLED, recovered.order("taker").getStatus());
        assertNoReservedBuy(TestSupport.account(recovered, "buyer"), "99600", 4);
        logBusiness("IOC 部分成交-剩余撤销且只释放剩余预留", result);
    }

    @Test
    void iocWithoutAnyFillCancelsWholeOrder() throws IOException {
        String file = "ioc-none.jsonl";
        MatchingService service = fresh(file);
        service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "110", 4));

        OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.LIMIT, "100", 10, TimeInForce.IOC, null));

        assertEquals(OrderStatus.CANCELLED, result.status());
        assertNull(result.rejectReason());
        assertEquals(0, result.filledQty());
        assertEquals(10, result.remainingQty());
        assertNoReservedBuy(TestSupport.account(service, "buyer"), "100000", 0);
        assertEquals(0, count(file, "ORDER_TRADED"));
        assertEquals(1, count(file, "ORDER_CANCELLED"));

        MatchingService recovered = restart(file);
        assertEquals(OrderStatus.CANCELLED, recovered.order("taker").getStatus());
        assertEquals(BigDecimal.ZERO, TestSupport.account(recovered, "buyer").getReservedCash());
        logBusiness("IOC 完全未成交-整单撤销", result);
    }

    @Test
    void marketOrderFullyFilledIsFilledWithoutReject() throws IOException {
        String file = "market-full.jsonl";
        MatchingService service = fresh(file);
        service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 5));

        OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.MARKET, null, 5, TimeInForce.GTC, null));

        assertFilled(result, 5);
        assertNoReservedBuy(TestSupport.account(service, "buyer"), "99500", 5);
        OrderResult duplicate = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.MARKET, null, 5, TimeInForce.GTC, null));
        assertFilled(duplicate, 5);
        assertTrue(duplicate.replay());

        MatchingService recovered = restart(file);
        OrderResult afterRestart = recovered.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.MARKET, null, 5, TimeInForce.GTC, null));
        assertFilled(afterRestart, 5);
        assertTrue(afterRestart.replay());
        assertEquals(OrderStatus.FILLED, recovered.order("taker").getStatus());
        assertEquals(0, count(file, "ORDER_REJECTED"));
        assertEquals(0, count(file, "ORDER_CANCELLED"));
        assertEquals(1, count(file, "ORDER_ADDED_TO_BOOK"));
        logBusiness("市价单全成-首次/重放/重启一致", result);
    }

    @Test
    void marketOrderPartiallyFilledRejectsRemainderAsNoLiquidity() throws IOException {
        String file = "market-partial.jsonl";
        MatchingService service = fresh(file);
        service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 3));

        OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.MARKET, null, 8, TimeInForce.GTC, null));

        assertEquals(OrderStatus.REJECTED, result.status());
        assertEquals(RejectReason.NO_LIQUIDITY, result.rejectReason());
        assertEquals(3, result.filledQty());
        assertEquals(5, result.remainingQty());
        assertNoReservedBuy(TestSupport.account(service, "buyer"), "99700", 3);
        assertEquals(1, count(file, "ORDER_REJECTED"));

        MatchingService recovered = restart(file);
        var recoveredOrder = recovered.order("taker");
        assertEquals(OrderStatus.REJECTED, recoveredOrder.getStatus());
        assertEquals(RejectReason.NO_LIQUIDITY, recoveredOrder.getRejectReason());
        assertNoReservedBuy(TestSupport.account(recovered, "buyer"), "99700", 3);
        logBusiness("市价单部分成交-剩余按无流动性拒单且账本不动", result);
    }

    @Test
    void marketOrderWithoutLiquidityRejectsWholeOrder() throws IOException {
        String file = "market-none.jsonl";
        MatchingService service = fresh(file);

        OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.MARKET, null, 8, TimeInForce.GTC, null));

        assertEquals(OrderStatus.REJECTED, result.status());
        assertEquals(RejectReason.NO_LIQUIDITY, result.rejectReason());
        assertEquals(0, result.filledQty());
        assertEquals(8, result.remainingQty());
        assertNoReservedBuy(TestSupport.account(service, "buyer"), "100000", 0);
        assertEquals(0, count(file, "ORDER_TRADED"));
        assertEquals(0, count(file, "ORDER_ADDED_TO_BOOK"));

        MatchingService recovered = restart(file);
        assertEquals(OrderStatus.REJECTED, recovered.order("taker").getStatus());
        assertEquals(RejectReason.NO_LIQUIDITY, recovered.order("taker").getRejectReason());
        logBusiness("市价单无流动性-整单拒单", result);
    }

    @Test
    void fokFullyFilledLeavesNoBookOrTerminalEvent() throws IOException {
        String file = "fok-full-consistency.jsonl";
        MatchingService service = fresh(file);
        service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 7));

        OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.MARKET, null, 7, TimeInForce.FOK, null));

        assertFilled(result, 7);
        assertNoReservedBuy(TestSupport.account(service, "buyer"), "99300", 7);
        assertEquals(0, count(file, "ORDER_REJECTED"));
        assertEquals(0, count(file, "ORDER_CANCELLED"));
        assertEquals(1, count(file, "ORDER_ADDED_TO_BOOK"));

        OrderResult duplicate = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.MARKET, null, 7, TimeInForce.FOK, null));
        assertFilled(duplicate, 7);
        assertTrue(duplicate.replay());

        MatchingService recovered = restart(file);
        OrderResult afterRestart = recovered.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.MARKET, null, 7, TimeInForce.FOK, null));
        assertFilled(afterRestart, 7);
        assertTrue(afterRestart.replay());
        assertEquals(OrderStatus.FILLED, recovered.order("taker").getStatus());
        logBusiness("FOK 全成-无挂簿/撤销/拒单事件且结论一致", result);
    }

    @Test
    void marketIocFullyFilledIsFilledWithoutCancel() throws IOException {
        String file = "market-ioc-full.jsonl";
        MatchingService service = fresh(file);
        service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 4));

        OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.MARKET, null, 4, TimeInForce.IOC, null));

        assertFilled(result, 4);
        assertNoReservedBuy(TestSupport.account(service, "buyer"), "99600", 4);
        assertEquals(0, count(file, "ORDER_CANCELLED"));
        assertEquals(0, count(file, "ORDER_REJECTED"));

        MatchingService recovered = restart(file);
        OrderResult afterRestart = recovered.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.MARKET, null, 4, TimeInForce.IOC, null));
        assertFilled(afterRestart, 4);
        assertTrue(afterRestart.replay());
        logBusiness("市价 IOC 全成-无撤销/拒单事件", result);
    }

    @Test
    void fokSellFullyFilledIsFilledWithoutTerminalEvent() throws IOException {
        String file = "fok-full-sell.jsonl";
        MatchingService service = fresh(file);
        service.submit(TestSupport.order("maker", "buyer", Side.BUY, OrderType.LIMIT, "100", 6));

        OrderResult result = service.submit(TestSupport.order("taker", "seller", Side.SELL,
                OrderType.LIMIT, "100", 6, TimeInForce.FOK, null));

        assertFilled(result, 6);
        Account seller = TestSupport.account(service, "seller");
        assertEquals(0, seller.getReservedPosition("BTCUSD"));
        assertEquals(94, seller.getPosition("BTCUSD"));
        assertEquals(0, count(file, "ORDER_CANCELLED"));
        assertEquals(0, count(file, "ORDER_REJECTED"));
        assertEquals(1, count(file, "ORDER_ADDED_TO_BOOK"));

        OrderResult duplicate = service.submit(TestSupport.order("taker", "seller", Side.SELL,
                OrderType.LIMIT, "100", 6, TimeInForce.FOK, null));
        assertFilled(duplicate, 6);
        MatchingService recovered = restart(file);
        assertEquals(OrderStatus.FILLED, recovered.order("taker").getStatus());
        logBusiness("FOK 卖单全成-无撤销/拒单事件", result);
    }

    @Test
    void gtcLimitFullyFilledIsNotBooked() throws IOException {
        String file = "gtc-full.jsonl";
        MatchingService service = fresh(file);
        service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 4));

        OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.LIMIT, "100", 4, TimeInForce.GTC, null));

        assertFilled(result, 4);
        assertEquals(1, count(file, "ORDER_ADDED_TO_BOOK"));

        MatchingService recovered = restart(file);
        assertEquals(OrderStatus.FILLED, recovered.order("taker").getStatus());
        assertEquals(OrderStatus.FILLED, recovered.order("maker").getStatus());
        logBusiness("GTC 限价单全成-不补挂单事件", result);
    }
}
