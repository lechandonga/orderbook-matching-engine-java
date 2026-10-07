package com.github.lechandonga.orderbookmatchingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.lechandonga.orderbookmatchingenginejava.api.OrderResult;
import com.github.lechandonga.orderbookmatchingenginejava.config.MatchingProperties;
import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingService;
import com.github.lechandonga.orderbookmatchingenginejava.event.EventType;
import com.github.lechandonga.orderbookmatchingenginejava.ledger.Account;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.model.RejectReason;
import com.github.lechandonga.orderbookmatchingenginejava.model.Side;
import com.github.lechandonga.orderbookmatchingenginejava.model.TimeInForce;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住“全成 / 部分成交 / 完全没成交”三类结论边界，
 * 以及首次返回、重启重放、重复提交三条路径下的终态一致性。
 * 每个用例使用独立事件文件，可单独、重复执行。
 */
class FillOutcomeBoundaryTest {
    private static final Logger log = LoggerFactory.getLogger(FillOutcomeBoundaryTest.class);
    private static final Map<String, int[]> stats = new LinkedHashMap<>();

    @TempDir
    Path tempDirectory;

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private void runCase(String business, String name, CheckedRunnable assertions) {
        int[] counters = stats.computeIfAbsent(business, ignored -> new int[2]);
        counters[0]++;
        boolean ok = false;
        try {
            assertions.run();
            ok = true;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            if (ok) {
                log.info("[业务:{}] 用例={} 成功=1 失败=0", business, name);
            } else {
                counters[1]++;
                log.info("[业务:{}] 用例={} 成功=0 失败=1", business, name);
            }
        }
    }

    @AfterAll
    static void summary() {
        stats.forEach((business, counters) -> log.info("[业务汇总:{}] 用例数={} 成功={} 失败={}",
                business, counters[0], counters[0] - counters[1], counters[1]));
    }

    private MatchingService freshService(String fileName) {
        MatchingProperties properties = TestSupport.properties();
        MatchingService service = TestSupport.service(tempDirectory.resolve(fileName),
                properties, TestSupport.T0);
        service.recover();
        return service;
    }

    private Path logPath(String fileName) {
        return tempDirectory.resolve(fileName);
    }

    /** 读取某张订单在事件记录中出现过的事件类型（按 seq 顺序）。 */
    private List<EventType> eventsFor(Path logFile, String clientOrderId) throws Exception {
        List<EventType> types = new ArrayList<>();
        for (String line : Files.readAllLines(logFile)) {
            JsonNode node = TestSupport.objectMapper().readTree(line);
            if (clientOrderId.equals(node.path("payload").path("clientOrderId").asText(null))) {
                types.add(EventType.valueOf(node.get("type").asText()));
            }
        }
        return types;
    }

    private void assertFilled(OrderResult result, long qty) {
        assertEquals(OrderStatus.FILLED, result.status());
        assertEquals(qty, result.filledQty());
        assertEquals(0, result.remainingQty());
        assertNull(result.rejectReason());
        assertNull(result.rejectMessage());
        assertFalse(result.replay());
    }

    // ---------- IOC：全成 ----------

    @Test
    void iocLimitBuyFullyFilledIsNotCancelled() {
        runCase("IOC 全成", "限价买单刚好全成", () -> {
            Path logFile = logPath("ioc-buy-full.jsonl");
            MatchingService service = freshService("ioc-buy-full.jsonl");
            service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 7));

            OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                    OrderType.LIMIT, "100", 7, TimeInForce.IOC, null));

            assertFilled(result, 7);
            Account buyer = TestSupport.account(service, "buyer");
            assertEquals(BigDecimal.ZERO, buyer.getReservedCash());
            assertEquals(new BigDecimal("99300"), buyer.getCash());
            assertEquals(7, buyer.getPosition("BTCUSD"));
            assertEquals(List.of(EventType.ORDER_ACCEPTED, EventType.ORDER_TRADED),
                    eventsFor(logFile, "taker"));
        });
    }

    @Test
    void iocLimitSellFullyFilledIsNotCancelled() {
        runCase("IOC 全成", "限价卖单刚好全成", () -> {
            Path logFile = logPath("ioc-sell-full.jsonl");
            MatchingService service = freshService("ioc-sell-full.jsonl");
            service.submit(TestSupport.order("maker", "buyer", Side.BUY, OrderType.LIMIT, "100", 7));

            OrderResult result = service.submit(TestSupport.order("taker", "seller", Side.SELL,
                    OrderType.LIMIT, "100", 7, TimeInForce.IOC, null));

            assertFilled(result, 7);
            Account seller = TestSupport.account(service, "seller");
            assertEquals(0, seller.getReservedPosition("BTCUSD"));
            assertEquals(93, seller.getPosition("BTCUSD"));
            assertEquals(new BigDecimal("100700"), seller.getCash());
            assertEquals(List.of(EventType.ORDER_ACCEPTED, EventType.ORDER_TRADED),
                    eventsFor(logFile, "taker"));
        });
    }

    @Test
    void iocMarketBuyFullyFilledIsNotCancelled() {
        runCase("IOC 全成", "市价买单刚好全成", () -> {
            Path logFile = logPath("ioc-market-full.jsonl");
            MatchingService service = freshService("ioc-market-full.jsonl");
            service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 5));

            OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                    OrderType.MARKET, null, 5, TimeInForce.IOC, null));

            assertFilled(result, 5);
            assertEquals(BigDecimal.ZERO, TestSupport.account(service, "buyer").getReservedCash());
            assertEquals(List.of(EventType.ORDER_ACCEPTED, EventType.ORDER_TRADED),
                    eventsFor(logFile, "taker"));
        });
    }

    // ---------- IOC：部分成交 ----------

    @Test
    void iocPartialFillCancelsOnlyRemainder() {
        runCase("IOC 部分成交", "只成交一部分时撤销剩余并释放剩余预留", () -> {
            Path logFile = logPath("ioc-partial.jsonl");
            MatchingService service = freshService("ioc-partial.jsonl");
            service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 4));

            OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                    OrderType.LIMIT, "100", 10, TimeInForce.IOC, null));

            assertEquals(OrderStatus.CANCELLED, result.status());
            assertNull(result.rejectReason());
            assertEquals(4, result.filledQty());
            assertEquals(6, result.remainingQty());
            Account buyer = TestSupport.account(service, "buyer");
            assertEquals(BigDecimal.ZERO, buyer.getReservedCash());
            assertEquals(new BigDecimal("99600"), buyer.getCash());
            assertEquals(4, buyer.getPosition("BTCUSD"));
            assertEquals(List.of(EventType.ORDER_ACCEPTED, EventType.ORDER_TRADED,
                    EventType.ORDER_CANCELLED), eventsFor(logFile, "taker"));
        });
    }

    // ---------- IOC：完全没成交 ----------

    @Test
    void iocNoLiquidityCancelsWholeOrderAndReleasesReservation() {
        runCase("IOC 全不成", "无对手盘时整单撤销且无成交", () -> {
            Path logFile = logPath("ioc-none.jsonl");
            MatchingService service = freshService("ioc-none.jsonl");

            OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                    OrderType.LIMIT, "100", 10, TimeInForce.IOC, null));

            assertEquals(OrderStatus.CANCELLED, result.status());
            assertNull(result.rejectReason());
            assertEquals(0, result.filledQty());
            assertEquals(10, result.remainingQty());
            Account buyer = TestSupport.account(service, "buyer");
            assertEquals(BigDecimal.ZERO, buyer.getReservedCash());
            assertEquals(new BigDecimal("100000"), buyer.getCash());
            assertEquals(0, buyer.getPosition("BTCUSD"));
            assertEquals(List.of(EventType.ORDER_ACCEPTED, EventType.ORDER_CANCELLED),
                    eventsFor(logFile, "taker"));
            assertFalse(Files.readString(logFile).contains(EventType.ORDER_TRADED.name()));
        });
    }

    // ---------- 市价单：全成 ----------

    @Test
    void marketBuyFullyFilledIsNotRejected() {
        runCase("市价单全成", "市价买单刚好全成", () -> {
            Path logFile = logPath("market-buy-full.jsonl");
            MatchingService service = freshService("market-buy-full.jsonl");
            service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 6));

            OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                    OrderType.MARKET, null, 6, TimeInForce.GTC, null));

            assertFilled(result, 6);
            assertEquals(new BigDecimal("100"), result.avgPrice());
            Account buyer = TestSupport.account(service, "buyer");
            assertEquals(BigDecimal.ZERO, buyer.getReservedCash());
            assertEquals(new BigDecimal("99400"), buyer.getCash());
            assertEquals(6, buyer.getPosition("BTCUSD"));
            assertEquals(List.of(EventType.ORDER_ACCEPTED, EventType.ORDER_TRADED),
                    eventsFor(logFile, "taker"));
        });
    }

    @Test
    void marketSellFullyFilledIsNotRejected() {
        runCase("市价单全成", "市价卖单刚好全成", () -> {
            Path logFile = logPath("market-sell-full.jsonl");
            MatchingService service = freshService("market-sell-full.jsonl");
            service.submit(TestSupport.order("maker", "buyer", Side.BUY, OrderType.LIMIT, "100", 6));

            OrderResult result = service.submit(TestSupport.order("taker", "seller", Side.SELL,
                    OrderType.MARKET, null, 6, TimeInForce.GTC, null));

            assertFilled(result, 6);
            Account seller = TestSupport.account(service, "seller");
            assertEquals(0, seller.getReservedPosition("BTCUSD"));
            assertEquals(94, seller.getPosition("BTCUSD"));
            assertEquals(new BigDecimal("100600"), seller.getCash());
            assertEquals(List.of(EventType.ORDER_ACCEPTED, EventType.ORDER_TRADED),
                    eventsFor(logFile, "taker"));
        });
    }

    // ---------- 市价单：部分成交 ----------

    @Test
    void marketPartialFillRejectsRemainderAsNoLiquidity() {
        runCase("市价单部分成交", "已成部分正常交收，未成交部分按无流动性拒单", () -> {
            Path logFile = logPath("market-partial.jsonl");
            MatchingService service = freshService("market-partial.jsonl");
            service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 4));

            OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                    OrderType.MARKET, null, 10, TimeInForce.GTC, null));

            assertEquals(OrderStatus.REJECTED, result.status());
            assertEquals(RejectReason.NO_LIQUIDITY, result.rejectReason());
            assertEquals(4, result.filledQty());
            assertEquals(6, result.remainingQty());
            Account buyer = TestSupport.account(service, "buyer");
            assertEquals(BigDecimal.ZERO, buyer.getReservedCash());
            assertEquals(new BigDecimal("99600"), buyer.getCash());
            assertEquals(4, buyer.getPosition("BTCUSD"));
            assertEquals(List.of(EventType.ORDER_ACCEPTED, EventType.ORDER_TRADED,
                    EventType.ORDER_REJECTED), eventsFor(logFile, "taker"));
        });
    }

    // ---------- 市价单：完全没成交 ----------

    @Test
    void marketNoLiquidityRejectsWholeOrderAndLeavesLedgerUntouched() {
        runCase("市价单全不成", "无对手盘时按无流动性拒单且账本不动", () -> {
            Path logFile = logPath("market-none.jsonl");
            MatchingService service = freshService("market-none.jsonl");

            OrderResult result = service.submit(TestSupport.order("taker", "buyer", Side.BUY,
                    OrderType.MARKET, null, 10, TimeInForce.GTC, null));

            assertEquals(OrderStatus.REJECTED, result.status());
            assertEquals(RejectReason.NO_LIQUIDITY, result.rejectReason());
            assertEquals(0, result.filledQty());
            assertEquals(10, result.remainingQty());
            Account buyer = TestSupport.account(service, "buyer");
            assertEquals(BigDecimal.ZERO, buyer.getReservedCash());
            assertEquals(new BigDecimal("100000"), buyer.getCash());
            assertEquals(0, buyer.getPosition("BTCUSD"));
            assertEquals(List.of(EventType.ORDER_ACCEPTED, EventType.ORDER_REJECTED),
                    eventsFor(logFile, "taker"));
            assertFalse(Files.readString(logFile).contains(EventType.ORDER_TRADED.name()));
        });
    }

    // ---------- 全成：首次返回 / 重复提交 / 重启重放 一致性 ----------

    @Test
    void fullyFilledConclusionIsStableAcrossReplayAndResubmit() {
        List<Scenario> scenarios = List.of(
                new Scenario("限价 GTC 买单全成", "limit-buy-gtc.jsonl",
                        "buyer", Side.BUY, OrderType.LIMIT, "100", TimeInForce.GTC,
                        "seller", Side.SELL),
                new Scenario("限价 IOC 卖单全成", "limit-sell-ioc.jsonl",
                        "seller", Side.SELL, OrderType.LIMIT, "100", TimeInForce.IOC,
                        "buyer", Side.BUY),
                new Scenario("市价 IOC 买单全成", "market-buy-ioc.jsonl",
                        "buyer", Side.BUY, OrderType.MARKET, null, TimeInForce.IOC,
                        "seller", Side.SELL),
                new Scenario("市价 GTC 卖单全成", "market-sell-gtc.jsonl",
                        "seller", Side.SELL, OrderType.MARKET, null, TimeInForce.GTC,
                        "buyer", Side.BUY),
                new Scenario("市价 FOK 买单全成", "market-buy-fok.jsonl",
                        "buyer", Side.BUY, OrderType.MARKET, null, TimeInForce.FOK,
                        "seller", Side.SELL),
                new Scenario("市价 FOK 卖单全成", "market-sell-fok.jsonl",
                        "seller", Side.SELL, OrderType.MARKET, null, TimeInForce.FOK,
                        "buyer", Side.BUY)
        );
        for (Scenario scenario : scenarios) {
            runCase("全成终态一致性", scenario.name(), () -> verifyFullyFilledStable(scenario));
        }
    }

    private void verifyFullyFilledStable(Scenario scenario) throws Exception {
        Path logFile = logPath(scenario.fileName());
        MatchingProperties properties = TestSupport.properties();
        MatchingService service = TestSupport.service(logFile, properties, TestSupport.T0);
        service.recover();
        service.submit(TestSupport.order("maker", scenario.makerAccount(), scenario.makerSide(),
                OrderType.LIMIT, "100", 5));

        OrderResult first = service.submit(TestSupport.order("taker", scenario.takerAccount(),
                scenario.takerSide(), scenario.takerType(), scenario.price(), 5,
                scenario.tif(), null));
        assertFilled(first, 5);

        OrderResult duplicate = service.submit(TestSupport.order("taker", scenario.takerAccount(),
                scenario.takerSide(), scenario.takerType(), scenario.price(), 5,
                scenario.tif(), null));
        assertEquals(OrderStatus.FILLED, duplicate.status());
        assertEquals(5, duplicate.filledQty());
        assertEquals(0, duplicate.remainingQty());
        assertNull(duplicate.rejectReason());
        assertTrue(duplicate.replay());

        MatchingService recovered = TestSupport.recovered(logFile, properties, TestSupport.T0.plusSeconds(1));
        assertEquals(OrderStatus.FILLED, recovered.order("taker").getStatus());
        assertNull(recovered.order("taker").getRejectReason());
        OrderResult afterRestart = recovered.submit(TestSupport.order("taker", scenario.takerAccount(),
                scenario.takerSide(), scenario.takerType(), scenario.price(), 5,
                scenario.tif(), null));
        assertEquals(OrderStatus.FILLED, afterRestart.status());
        assertEquals(5, afterRestart.filledQty());
        assertNull(afterRestart.rejectReason());
        assertTrue(afterRestart.replay());

        assertEquals(List.of(EventType.ORDER_ACCEPTED, EventType.ORDER_TRADED),
                eventsFor(logFile, "taker"));
    }

    private record Scenario(String name, String fileName,
                            String takerAccount, Side takerSide, OrderType takerType, String price,
                            TimeInForce tif, String makerAccount, Side makerSide) {
    }
}
