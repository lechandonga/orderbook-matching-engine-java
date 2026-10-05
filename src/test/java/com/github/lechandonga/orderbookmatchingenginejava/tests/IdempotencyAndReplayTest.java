package com.github.lechandonga.orderbookmatchingenginejava.tests;

import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.domain.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.domain.RejectReason;
import com.github.lechandonga.orderbookmatchingenginejava.domain.Side;
import com.github.lechandonga.orderbookmatchingenginejava.engine.AccountSnapshot;
import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lechandonga.orderbookmatchingenginejava.engine.SubmitResult;
import com.github.lechandonga.orderbookmatchingenginejava.event.LedgerCorruptedException;
import com.github.lechandonga.orderbookmatchingenginejava.order.OrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.lechandonga.orderbookmatchingenginejava.tests.support.EngineTestSupport.AAPL;
import static com.github.lechandonga.orderbookmatchingenginejava.tests.support.EngineTestSupport.newEngine;
import static com.github.lechandonga.orderbookmatchingenginejava.tests.support.EngineTestSupport.tempLedger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务用例组 3：幂等提交、重启重放一致性、事件损坏显式报错。
 */
@DisplayName("业务-幂等与恢复：重复提交一致 / 重放逐笔一致 / 损坏报错")
class IdempotencyAndReplayTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAndReplayTest.class);

    private Path ledger;
    private MatchingEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        ledger = tempLedger();
        engine = newEngine(ledger);
    }

    @Test
    @DisplayName("用例3.1 相同外部订单号重复提交返回首次结论（不重复成交）；字段冲突明确报冲突")
    void duplicateSubmissionReturnsFirstResult() {
        log.info(">>> [幂等] bob 卖 10@150；alice 用同一订单号提交两次买单");
        engine.submit(limit("ID-SELL", "bob", Side.SELL, "150", 10));
        OrderRequest firstReq = limit("ID-BUY", "alice", Side.BUY, "150", 10);
        SubmitResult first = engine.submit(firstReq);
        assertEquals(OrderStatus.FILLED, first.status());

        SubmitResult second = engine.submit(limit("ID-BUY", "alice", Side.BUY, "150", 10));
        assertTrue(second.idempotentReplay());
        assertEquals(first.status(), second.status());
        assertEquals(first.filledQuantity(), second.filledQuantity());
        assertTrue(second.fills().isEmpty(), "幂等重放不重新撮合，fills 为空但结论一致");

        AccountSnapshot bob = engine.snapshotAccount("bob");
        assertEquals(0, new BigDecimal("51500").compareTo(bob.cashTotal()));
        log.info("<<< 第二次提交未再成交：bob 现金仅增加一次 1500");

        SubmitResult conflict = engine.submit(limit("ID-BUY", "alice", Side.BUY, "160", 10));
        assertFalse(conflict.accepted());
        assertEquals(RejectReason.DUPLICATE_ORDER_ID, conflict.rejectReason());
        log.info("<<< 同订单号不同价格得到 DUPLICATE_ORDER_ID 冲突，不覆盖首次结果");
    }

    @Test
    @DisplayName("用例3.2 拒单同样幂等：被风控拒绝的订单号重放提交返回同一拒单原因")
    void rejectedOrderIsIdempotent() {
        log.info(">>> [幂等-拒单] 超额单连续提交两次，原因均为 INSUFFICIENT_CASH");
        OrderRequest overCash = limit("ID-REJ", "carol", Side.BUY, "11", 100);
        SubmitResult first = engine.submit(overCash);
        assertEquals(RejectReason.INSUFFICIENT_CASH, first.rejectReason());
        SubmitResult second = engine.submit(overCash);
        assertTrue(second.idempotentReplay());
        assertEquals(RejectReason.INSUFFICIENT_CASH, second.rejectReason());
        log.info("<<< 拒单结论可重复，且只记录首次拒单的业务语义");
    }

    @Test
    @DisplayName("用例3.3 重启重放：部分成交、撤单后的订单状态与账本与重放前逐笔一致")
    void replayProducesIdenticalState() throws Exception {
        log.info(">>> [恢复-重放] 构造成交/部分成交/撤单混合场景后重建引擎");
        engine.submit(limit("RL-SELL1", "bob", Side.SELL, "150", 20));
        engine.submit(limit("RL-BUY1", "alice", Side.BUY, "150", 50));
        engine.submit(limit("RL-SELL2", "alice", Side.SELL, "200", 5));
        SubmitResult canceled = engine.submit(limit("RL-BUY2", "alice", Side.BUY, "90", 5));
        engine.cancel(new com.github.lechandonga.orderbookmatchingenginejava.order.CancelRequest(
                "RL-BUY2", "alice"));

        AccountSnapshot aliceBefore = engine.snapshotAccount("alice");
        AccountSnapshot bobBefore = engine.snapshotAccount("bob");
        long eventsBefore = engine.lastEventSeq();

        MatchingEngine restored = newEngine(ledger);
        AccountSnapshot aliceAfter = restored.snapshotAccount("alice");
        AccountSnapshot bobAfter = restored.snapshotAccount("bob");
        assertEquals(aliceBefore, aliceAfter);
        assertEquals(bobBefore, bobAfter);
        assertEquals(OrderStatus.PARTIALLY_FILLED, restored.getOrder("RL-BUY1").status());
        assertEquals(20, restored.getOrder("RL-BUY1").filledQty());
        assertEquals(30, restored.getOrder("RL-BUY1").remainingQty());
        assertEquals(OrderStatus.CANCELED, restored.getOrder("RL-BUY2").status());
        assertEquals(OrderStatus.NEW, restored.getOrder("RL-SELL2").status());
        assertEquals(1, restored.bookDepth(AAPL, Side.BUY));
        assertEquals(1, restored.bookDepth(AAPL, Side.SELL));
        assertEquals(eventsBefore, restored.lastEventSeq());
        log.info("<<< 重放后双方资金/持仓、订单状态、簿深度、事件数全部一致");

        SubmitResult afterReboot = restored.submit(
                limit("RL-BUY1", "alice", Side.BUY, "150", 50));
        assertTrue(afterReboot.idempotentReplay());
        assertEquals(OrderStatus.PARTIALLY_FILLED, afterReboot.status());
        log.info("<<< 重启后用旧订单号查询仍返回重放前的部分成交结论");
    }

    @Test
    @DisplayName("用例3.4 事件记录被截断（缺失）或被篡改（checksum 坏）时启动必须显式报错")
    void corruptedLedgerFailsLoudly() throws Exception {
        engine.submit(limit("COR-1", "bob", Side.SELL, "100", 5));

        Path truncated = Files.createTempFile("matching-truncated-", ".jsonl");
        Path tampered = Files.createTempFile("matching-tampered-", ".jsonl");
        java.util.List<String> lines = Files.readAllLines(ledger, StandardCharsets.UTF_8);
        // 去掉最后一行后再追加一条 seq 不连续的伪造记录，模拟记录缺失而非文件尾部空白
        java.util.List<String> subLines = lines.subList(0, lines.size() - 1);
        Files.write(truncated, subLines, StandardCharsets.UTF_8);
        Files.writeString(truncated,
                lines.get(lines.size() - 1).replaceAll("\"seq\":\\d+", "\"seq\":999999")
                        + System.lineSeparator(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        String last = lines.get(lines.size() - 1).replace("\"quantity\":5", "\"quantity\":9");
        Files.writeString(tampered, String.join(System.lineSeparator(),
                lines.subList(0, lines.size() - 1)) + System.lineSeparator() + last,
                StandardCharsets.UTF_8);

        LedgerCorruptedException truncateError = assertThrows(LedgerCorruptedException.class,
                () -> newEngine(truncated));
        LedgerCorruptedException tamperError = assertThrows(LedgerCorruptedException.class,
                () -> newEngine(tampered));
        assertTrue(truncateError.getMessage().contains("序号不连续")
                || truncateError.getMessage().contains("checksum"));
        assertTrue(tamperError.getMessage().contains("checksum"));
        log.info("<<< 截断 -> '{}'；篡改 -> '{}'，均拒绝启动而非静默跳过",
                truncateError.getMessage(), tamperError.getMessage());
    }

    private static OrderRequest limit(String id, String account, Side side,
                                      String price, long qty) {
        return new OrderRequest(id, account, AAPL, side, OrderType.LIMIT,
                new BigDecimal(price), qty);
    }
}
