package com.github.lechandonga.orderbookmatchingenginejava.tests;

import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.domain.Side;
import com.github.lechandonga.orderbookmatchingenginejava.engine.AccountSnapshot;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.lechandonga.orderbookmatchingenginejava.tests.support.EngineTestSupport.AAPL;
import static com.github.lechandonga.orderbookmatchingenginejava.tests.support.EngineTestSupport.newEngine;
import static com.github.lechandonga.orderbookmatchingenginejava.tests.support.EngineTestSupport.tempLedger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务用例组 4：并发提交 / 撤单不超卖、不重复成交、不丢单，且资金与持仓守恒。
 */
@DisplayName("业务-并发：高并发买卖/撤单下不超卖不丢单且账本守恒")
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    private Path ledger;
    private MatchingEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        ledger = tempLedger();
        engine = newEngine(ledger);
    }

    @Test
    @DisplayName("用例4.1 100 个买单争抢 50 股卖盘：恰好成交 50，其余因 IOC 撤余/资金规则结束，持仓守恒")
    void concurrentBuyersNoOversell() throws Exception {
        log.info(">>> [并发-防超卖] bob 挂卖 50@100；100 个账户并发各买 1@100");
        // 为简化，复用 alice/carol 之外的资金：给 alice 多次不同订单号买单，
        // 每个买 1 股，验证成交总量受卖盘数量约束。
        engine.submit(limit("CC-SELL", "bob", Side.SELL, "100", 50));

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger filled = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    SubmitResult result = engine.submit(
                            limit("CC-BUY-" + index, "buyer" + index, Side.BUY, "100", 1));
                    if (result.filledQuantity() > 0) {
                        filled.addAndGet((int) result.filledQuantity());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(50, filled.get(), "50 股卖盘最多成交 50 股，绝不能超卖");
        AccountSnapshot bob = engine.snapshotAccount("bob");
        assertEquals(0L, bob.positions().get(AAPL), "bob 初始 50 股，全部卖出余 0");
        long buyerPositions = 0;
        for (int i = 0; i < threads; i++) {
            buyerPositions += engine.snapshotAccount("buyer" + i).positions()
                    .getOrDefault(AAPL, 0L);
        }
        assertEquals(50, buyerPositions, "100 个买家合计恰好吃进 50 股");
        log.info("<<< 总成交 {} 股；bob 持仓 {}、买家合计持仓 {}，无超卖/重复成交",
                filled.get(), bob.positions().get(AAPL), buyerPositions);
    }

    @Test
    @DisplayName("用例4.2 并发撤单与成交：撤掉的单绝不参与成交，撤单/成交数量之和守恒")
    void concurrentCancelCannotTradeCanceledOrder() throws Exception {
        log.info(">>> [并发-撤单] 50 个独立买家各挂买 1@100，撤单线程与 bob 的卖单并发冲击")
                ;
        for (int i = 0; i < 50; i++) {
            engine.submit(limit("CX-BUY-" + i, "buyer" + i, Side.BUY, "100", 1));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 50; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    engine.cancel(new CancelRequest("CX-BUY-" + index, "buyer" + index));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    engine.submit(limit("CX-SELL-" + index, "bob", Side.SELL, "100", 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long traded = 0;
        long canceled = 0;
        for (int i = 0; i < 50; i++) {
            var order = engine.getOrder("CX-BUY-" + i);
            if (order.status() == OrderStatus.CANCELED) {
                canceled += order.originalQty() - order.filledQty();
            }
            traded += order.filledQty();
        }
        assertEquals(50, traded + canceled,
                "50 个买单的去向只能是成交或撤销，无丢单");
        long buyerPositions = 0;
        for (int i = 0; i < 50; i++) {
            buyerPositions += engine.snapshotAccount("buyer" + i).positions()
                    .getOrDefault(AAPL, 0L);
        }
        assertEquals(traded, buyerPositions, "成交多少买家就收多少货");
        AccountSnapshot bob = engine.snapshotAccount("bob");
        assertEquals(50 - traded, bob.positions().get(AAPL), "卖家持仓减少量等于成交量");
        log.info("<<< 买单成交 {}、撤销 {}，合计 50；买家持仓 {}、bob 持仓 {}，无丢单/无已撤单成交",
                traded, canceled, buyerPositions, bob.positions().get(AAPL));
    }

    private static OrderRequest limit(String id, String account, Side side,
                                      String price, long qty) {
        return new OrderRequest(id, account, AAPL, side, OrderType.LIMIT,
                new BigDecimal(price), qty);
    }
}
