package com.github.lechandonga.orderbookmatchingenginejava.tests;

import com.github.lechandonga.orderbookmatchingenginejava.api.InternalTradingGateway;
import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.domain.RejectReason;
import com.github.lechandonga.orderbookmatchingenginejava.domain.Side;
import com.github.lechandonga.orderbookmatchingenginejava.engine.CancelResult;
import com.github.lechandonga.orderbookmatchingenginejava.engine.SubmitResult;
import com.github.lechandonga.orderbookmatchingenginejava.order.CancelRequest;
import com.github.lechandonga.orderbookmatchingenginejava.order.OrderRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;

import static com.github.lechandonga.orderbookmatchingenginejava.tests.support.EngineTestSupport.AAPL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务用例组 6：HTTP 对外入口与进程内调用入口的判定一致性（同源引擎）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("业务-双入口一致：HTTP 与内部网关对同一请求结论一致")
class ApplicationTests {

    private static final Logger log = LoggerFactory.getLogger(ApplicationTests.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private InternalTradingGateway internalGateway;

    @DynamicPropertySource
    static void ledgerLocation(DynamicPropertyRegistry registry) throws Exception {
        Path freshLedger = Files.createTempFile("matching-spring-", ".jsonl");
        Files.writeString(freshLedger, "");
        registry.add("matching.ledger-path", () -> freshLedger.toString());
    }

    @Test
    @DisplayName("用例6.1 同一订单号先内部提交成交，HTTP 再提交得到幂等结论；超额单两处拒绝原因一致")
    void httpAndInternalEntriesAgree() {
        log.info(">>> [双入口一致] 内部网关先挂卖单+成交买单，HTTP 重放买单；再比较超额拒单");

        SubmitResult internalSell = internalGateway.submitOrder(
                new OrderRequest("HTTP-SELL", "bob", AAPL, Side.SELL,
                        OrderType.LIMIT, new BigDecimal("120"), 5L));
        assertTrue(internalSell.accepted());

        SubmitResult internalBuy = internalGateway.submitOrder(
                new OrderRequest("HTTP-BUY", "alice", AAPL, Side.BUY,
                        OrderType.LIMIT, new BigDecimal("120"), 5L));
        assertTrue(internalBuy.accepted());

        ResponseEntity<SubmitResult> httpRepeat = restTemplate.postForEntity(
                baseUrl() + "/api/orders",
                new OrderRequest("HTTP-BUY", "alice", AAPL, Side.BUY,
                        OrderType.LIMIT, new BigDecimal("120"), 5L),
                SubmitResult.class);
        assertEquals(HttpStatus.OK, httpRepeat.getStatusCode());
        assertTrue(httpRepeat.getBody().idempotentReplay());
        assertEquals(internalBuy.status(), httpRepeat.getBody().status());
        assertEquals(internalBuy.filledQuantity(), httpRepeat.getBody().filledQuantity());
        log.info("<<< HTTP 重复提交返回与内部首次提交一致的 FILLED/5，未二次成交");

        OrderRequest overCash = new OrderRequest("HTTP-RISK", "carol", AAPL,
                Side.BUY, OrderType.LIMIT, new BigDecimal("20"), 100L);
        SubmitResult internalReject = internalGateway.submitOrder(overCash);
        assertFalse(internalReject.accepted());
        assertEquals(RejectReason.INSUFFICIENT_CASH, internalReject.rejectReason());

        ResponseEntity<String> httpReject = restTemplate.postForEntity(
                baseUrl() + "/api/orders", overCash, String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, httpReject.getStatusCode());
        assertTrue(httpReject.getBody().contains("INSUFFICIENT_CASH"));
        log.info("<<< 超额单内部返回 INSUFFICIENT_CASH，HTTP 返回 422 且原因码相同");

        ResponseEntity<String> invalid = restTemplate.postForEntity(
                baseUrl() + "/api/orders",
                new OrderRequest("HTTP-BAD", "alice", AAPL, Side.BUY,
                        OrderType.LIMIT, BigDecimal.ZERO, 1L),
                String.class);
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertTrue(invalid.getBody().contains("INVALID_PRICE"));
        log.info("<<< 非法价格 HTTP 返回 400 INVALID_PRICE，拒绝原因可区分");

        ResponseEntity<CancelResult> cancelMissing = restTemplate.postForEntity(
                baseUrl() + "/api/orders/cancel",
                new CancelRequest("NO-SUCH", "bob"),
                CancelResult.class);
        assertEquals(HttpStatus.NOT_FOUND, cancelMissing.getStatusCode());
        log.info("<<< 撤不存在的单 HTTP 返回 404 ORDER_NOT_FOUND");
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}
