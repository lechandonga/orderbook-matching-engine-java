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
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionInstructionTest {
    private static final Logger log = LoggerFactory.getLogger(ExecutionInstructionTest.class);

    @TempDir
    Path tempDirectory;

    @Test
    void iocFillsAvailableAndCancelsRemainder() {
        MatchingProperties properties = TestSupport.properties();
        MatchingService service = TestSupport.service(tempDirectory.resolve("ioc.jsonl"), properties, TestSupport.T0);
        service.recover();
        service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 4));

        OrderResult result = service.submit(TestSupport.order("ioc", "buyer", Side.BUY,
                OrderType.LIMIT, "100", 10, TimeInForce.IOC, null));

        assertEquals(OrderStatus.CANCELLED, result.status());
        assertEquals(4, result.filledQty());
        assertEquals(6, result.remainingQty());
        Account buyer = TestSupport.account(service, "buyer");
        assertEquals(BigDecimal.ZERO, buyer.getReservedCash());
        assertEquals(new BigDecimal("99600"), buyer.getCash());
        assertEquals(4, buyer.getPosition("BTCUSD"));
        logBusiness("IOC 部分成交后剩余撤销并释放预留", result);
    }

    @Test
    void fokFillsCompletelyWhenLiquidityIsEnough() {
        MatchingProperties properties = TestSupport.properties();
        MatchingService service = TestSupport.service(tempDirectory.resolve("fok-full.jsonl"), properties, TestSupport.T0);
        service.recover();
        service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 7));

        OrderResult result = service.submit(TestSupport.order("fok", "buyer", Side.BUY,
                OrderType.MARKET, null, 7, TimeInForce.FOK, null));

        assertEquals(OrderStatus.FILLED, result.status());
        assertEquals(7, result.filledQty());
        assertEquals(0, result.remainingQty());
        assertEquals(BigDecimal.ZERO, TestSupport.account(service, "buyer").getReservedCash());
        logBusiness("FOK 流动性充足时整单成交", result);
    }

    @Test
    void fokRejectsAtomicallyWhenLiquidityIsShort() throws java.io.IOException {
        MatchingProperties properties = TestSupport.properties();
        Path log = tempDirectory.resolve("fok-short.jsonl");
        MatchingService service = TestSupport.service(log, properties, TestSupport.T0);
        service.recover();
        service.submit(TestSupport.order("maker", "seller", Side.SELL, OrderType.LIMIT, "100", 3));
        Account buyerBefore = TestSupport.account(service, "buyer");
        BigDecimal cashBefore = buyerBefore.getCash();
        long positionBefore = buyerBefore.getPosition("BTCUSD");

        OrderResult result = service.submit(TestSupport.order("fok", "buyer", Side.BUY,
                OrderType.LIMIT, "100", 10, TimeInForce.FOK, null));

        assertEquals(OrderStatus.REJECTED, result.status());
        assertEquals(RejectReason.WOULD_NOT_FILL, result.rejectReason());
        assertEquals(0, result.filledQty());
        assertEquals(BigDecimal.ZERO, buyerBefore.getReservedCash());
        assertEquals(cashBefore, buyerBefore.getCash());
        assertEquals(positionBefore, buyerBefore.getPosition("BTCUSD"));
        assertTrue(java.util.regex.Pattern.compile("\"type\":\"ORDER_REJECTED\"").matcher(
                java.nio.file.Files.readString(log)).results().count() == 1);
        assertFalse(java.nio.file.Files.readString(log).contains("ORDER_TRADED"));

        OrderResult duplicate = service.submit(TestSupport.order("fok", "buyer", Side.BUY,
                OrderType.LIMIT, "100", 10, TimeInForce.FOK, null));
        assertEquals(result.status(), duplicate.status());
        assertEquals(result.rejectReason(), duplicate.rejectReason());
        assertTrue(duplicate.replay());
        MatchingService recovered = TestSupport.recovered(log, properties, TestSupport.T0.plusSeconds(1));
        OrderResult afterRestart = recovered.submit(TestSupport.order("fok", "buyer", Side.BUY,
                OrderType.LIMIT, "100", 10, TimeInForce.FOK, null));
        assertEquals(RejectReason.WOULD_NOT_FILL, afterRestart.rejectReason());
        assertTrue(afterRestart.replay());
        logBusiness("FOK 流动性不足全撤、幂等与重启后结论一致", result);
    }

    private void logBusiness(String business, OrderResult result) {
        log.info("[业务:{}] 用例数=1 成功=1 失败=0 结果 status={}, filled={}, reason={}",
                business, result.status(), result.filledQty(), result.rejectReason());
    }
}
