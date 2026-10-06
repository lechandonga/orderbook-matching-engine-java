package com.github.lechandonga.orderbookmatchingenginejava.tests;

import com.github.lechandonga.orderbookmatchingenginejava.api.OrderResult;
import com.github.lechandonga.orderbookmatchingenginejava.config.MatchingProperties;
import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingService;
import com.github.lechandonga.orderbookmatchingenginejava.ledger.Account;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpiryTest {
    private static final Logger log = LoggerFactory.getLogger(ExpiryTest.class);

    @TempDir
    Path tempDirectory;

    @Test
    void expiresOnlyRemainingAfterPartialFillAndReleasesReservation() {
        MatchingProperties properties = TestSupport.properties();
        Path eventLog = tempDirectory.resolve("expiry.jsonl");
        Instant deadline = TestSupport.T0.plusSeconds(10);
        MatchingService service = TestSupport.service(eventLog, properties, TestSupport.T0);
        service.recover();
        service.submit(TestSupport.order("buy-resting", "buyer", Side.BUY, OrderType.LIMIT,
                "100", 10, null, deadline));
        service.submit(TestSupport.order("seller-4", "seller", Side.SELL, OrderType.LIMIT, "100", 4));

        OrderResult firstExpiry = service.expireDue(TestSupport.T0.plusSeconds(9)).stream().findFirst().orElse(null);
        assertEquals(null, firstExpiry);
        OrderResult expired = service.expireDue(deadline).get(0);
        OrderResult secondExpiry = service.expireDue(deadline.plusSeconds(1)).stream().findFirst().orElse(null);

        assertEquals(OrderStatus.EXPIRED, expired.status());
        assertEquals(4, expired.filledQty());
        assertEquals(6, expired.remainingQty());
        assertEquals(null, secondExpiry);
        Account buyer = TestSupport.account(service, "buyer");
        Account seller = TestSupport.account(service, "seller");
        assertEquals(BigDecimal.ZERO, buyer.getReservedCash());
        assertEquals(new BigDecimal("99600"), buyer.getCash());
        assertEquals(4, buyer.getPosition("BTCUSD"));
        assertEquals(96, seller.getPosition("BTCUSD"));
        assertEquals(new BigDecimal("100400"), seller.getCash());

        MatchingService recovered = TestSupport.recovered(eventLog, properties, deadline.plusSeconds(2));
        OrderResult duplicateAfterRestart = recovered.expireDue(deadline.plusSeconds(3)).stream().findFirst().orElse(null);
        assertEquals(null, duplicateAfterRestart);
        assertEquals(OrderStatus.EXPIRED, recovered.order("buy-resting").getStatus());
        assertEquals(4, recovered.order("buy-resting").getFilledQty());
        assertEquals(BigDecimal.ZERO, TestSupport.account(recovered, "buyer").getReservedCash());
        log.info("[业务:带截止时间订单到期] 用例数=1 成功=1 失败=0 结果 status={}, filled={}, remaining={}",
                expired.status(), expired.filledQty(), expired.remainingQty());
    }
}
