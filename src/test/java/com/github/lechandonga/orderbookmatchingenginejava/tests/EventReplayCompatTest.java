package com.github.lechandonga.orderbookmatchingenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.orderbookmatchingenginejava.config.MatchingProperties;
import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingService;
import com.github.lechandonga.orderbookmatchingenginejava.event.EventLogException;
import com.github.lechandonga.orderbookmatchingenginejava.event.EventStore;
import com.github.lechandonga.orderbookmatchingenginejava.event.EventType;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderStatus;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.util.HexFormat;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventReplayCompatTest {
    private static final Logger log = LoggerFactory.getLogger(EventReplayCompatTest.class);

    @TempDir
    Path tempDirectory;

    @Test
    void replaysMixedLegacyAndCurrentEventRecords() {
        MatchingProperties properties = TestSupport.properties();
        Path file = tempDirectory.resolve("mixed.jsonl");
        EventStore store = TestSupport.eventStore(file);
        MatchingService producer = new MatchingService(store,
                new com.github.lechandonga.orderbookmatchingenginejava.ledger.AccountRepository(properties),
                properties, java.time.Clock.fixed(TestSupport.T0, java.time.ZoneOffset.UTC));
        producer.recover();
        producer.submit(TestSupport.order("new-maker", "seller", Side.SELL, OrderType.LIMIT, "101", 3));
        appendLegacy(file, 3, EventType.ORDER_ACCEPTED,
                "{\"clientOrderId\":\"old-maker\",\"accountId\":\"seller\",\"symbol\":\"BTCUSD\","
                        + "\"side\":\"SELL\",\"orderType\":\"LIMIT\",\"price\":100,\"qty\":5,"
                        + "\"reservePrice\":100,\"filledQty\":0}");
        appendLegacy(file, 4, EventType.ORDER_ADDED_TO_BOOK,
                "{\"clientOrderId\":\"old-maker\",\"accountId\":\"seller\",\"symbol\":\"BTCUSD\","
                        + "\"side\":\"SELL\",\"orderType\":\"LIMIT\",\"price\":100,\"qty\":5,"
                        + "\"reservePrice\":100,\"filledQty\":0}");

        MatchingService recovered = TestSupport.recovered(file, properties, TestSupport.T0.plusSeconds(1));
        var result = recovered.submit(TestSupport.order("taker", "buyer", Side.BUY,
                OrderType.MARKET, null, 8, com.github.lechandonga.orderbookmatchingenginejava.model.TimeInForce.IOC, null));

        assertEquals(OrderStatus.FILLED, result.status());
        assertEquals(8, result.filledQty());
        assertEquals(OrderStatus.FILLED, recovered.order("old-maker").getStatus());
        assertEquals(OrderStatus.FILLED, recovered.order("new-maker").getStatus());
        log.info("[业务:新旧事件混合重放-全成结论一致] 用例数=1 成功=1 失败=0 结果 status={}, filled={}",
                result.status(), result.filledQty());
    }

    @Test
    void rejectsCorruptedRecordInsteadOfSkippingIt() throws java.io.IOException {
        Path file = tempDirectory.resolve("corrupt.jsonl");
        EventStore store = TestSupport.eventStore(file);
        Files.writeString(file, "{broken\n", StandardCharsets.UTF_8);
        EventLogException error = assertThrows(EventLogException.class, store::readAll);
        assertEquals(true, error.getMessage().contains("不是合法 JSON"));
        log.info("[业务:损坏事件严格失败] 用例数=1 成功=1 失败=0 结果={}", error.getMessage());
    }

    private void appendLegacy(Path file, long seq, EventType type, String payload) {
        CRC32 crc = new CRC32();
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        String line = "{\"version\":1,\"seq\":" + seq + ",\"type\":\"" + type.name()
                + "\",\"payload\":" + payload + ",\"crc\":\""
                + HexFormat.of().toHexDigits(crc.getValue()) + "\"}\n";
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
