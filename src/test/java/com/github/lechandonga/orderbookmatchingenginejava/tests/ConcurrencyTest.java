package com.github.lechandonga.orderbookmatchingenginejava.tests;

import com.github.lechandonga.orderbookmatchingenginejava.api.OrderResult;
import com.github.lechandonga.orderbookmatchingenginejava.api.SubmitOrderRequest;
import com.github.lechandonga.orderbookmatchingenginejava.config.MatchingProperties;
import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingService;
import com.github.lechandonga.orderbookmatchingenginejava.ledger.Account;
import com.github.lechandonga.orderbookmatchingenginejava.ledger.AccountRepository;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发下的撮合/到期/撤单串行性验证：临界区保证不超卖、不重复扣减、同一订单只终结一次。
 */
class ConcurrencyTest {
    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    @TempDir
    Path tempDirectory;

    @Test
    void concurrentFokSubmissionsNeverOversell() throws Exception {
        MatchingProperties properties = TestSupport.properties();
        MatchingService service = TestSupport.service(
                tempDirectory.resolve("concurrent-fok.jsonl"), properties, TestSupport.T0);
        service.recover();
        service.submit(TestSupport.order("seller-resting", "seller",
                Side.SELL, OrderType.LIMIT, "100", 100));

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<OrderResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                barrier.await();
                return service.submit(TestSupport.order("fok-" + index, "buyer",
                        Side.BUY, OrderType.LIMIT, "100", 10, TimeInForce.FOK, null));
            }));
        }
        List<OrderResult> results = new ArrayList<>();
        for (Future<OrderResult> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long filledOrders = results.stream().filter(r -> r.status() == OrderStatus.FILLED).count();
        long rejectedOrders = results.stream()
                .filter(r -> r.status() == OrderStatus.REJECTED && r.rejectReason() == RejectReason.WOULD_NOT_FILL)
                .count();
        long totalFilled = results.stream().mapToLong(OrderResult::filledQty).sum();
        assertEquals(10, filledOrders, "只有 10 张 FOK 能拿到流动性");
        assertEquals(40, rejectedOrders, "其余 FOK 全部因 WOULD_NOT_FILL 拒单");
        assertEquals(100, totalFilled, "总成交量恰好等于挂单量，不超卖");

        Account buyer = TestSupport.account(service, "buyer");
        Account seller = TestSupport.account(service, "seller");
        assertEquals(BigDecimal.ZERO, buyer.getReservedCash(), "拒单不残留预留资金");
        assertEquals(100, buyer.getPosition("BTCUSD"));
        assertEquals(0, seller.getPosition("BTCUSD"), "卖方持仓恰好交收完");
        assertEquals(new BigDecimal("110000"), seller.getCash());
        log.info("[业务:并发 FOK 不超卖] 用例数=1 成功=1 失败=0 结果 全成={} 全撤={} 总成交量={}",
                filledOrders, rejectedOrders, totalFilled);
    }

    @Test
    void expiryRacesCancelButOrderTerminatesExactlyOnce() throws Exception {
        MatchingProperties properties = TestSupport.properties();
        Path eventLog = tempDirectory.resolve("expiry-cancel-race.jsonl");
        Instant deadline = TestSupport.T0.plusSeconds(10);
        MatchingService service = TestSupport.service(eventLog, properties, TestSupport.T0);
        service.recover();
        service.submit(TestSupport.order("expiring-buy", "buyer", Side.BUY,
                OrderType.LIMIT, "100", 10, TimeInForce.GTC, deadline));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<OrderResult> cancelFuture = pool.submit(() -> {
            barrier.await();
            return service.cancel("expiring-buy", "buyer");
        });
        Future<List<OrderResult>> expireFuture = pool.submit(() -> {
            barrier.await();
            return service.expireDue(deadline);
        });
        OrderResult cancelResult = cancelFuture.get(10, TimeUnit.SECONDS);
        List<OrderResult> expired = expireFuture.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        long terminalEvents = expired.size();
        boolean terminalOnce = cancelResult.status() == OrderStatus.CANCELLED
                ? terminalEvents == 0
                : terminalEvents == 1 && expired.get(0).status() == OrderStatus.EXPIRED;
        assertTrue(terminalOnce, "到期与撤单竞争时订单只能终结一次");

        OrderResult secondCancel = service.cancel("expiring-buy", "buyer");
        assertTrue(secondCancel.replay(), "终结后重复操作只回放首次结论");
        assertEquals(BigDecimal.ZERO, TestSupport.account(service, "buyer").getReservedCash(),
                "终结后预留全部释放且不重复释放");

        MatchingService recovered = TestSupport.recovered(eventLog, properties, deadline.plusSeconds(5));
        assertTrue(recovered.expireDue(deadline.plusSeconds(6)).isEmpty(), "重启后不重复失效");
        assertEquals(service.order("expiring-buy").getStatus(), recovered.order("expiring-buy").getStatus());
        log.info("[业务:到期与撤单并发] 用例数=1 成功=1 失败=0 结果 cancel={} expired={} 最终状态={}",
                cancelResult.status(), terminalEvents, recovered.order("expiring-buy").getStatus());
    }
}
