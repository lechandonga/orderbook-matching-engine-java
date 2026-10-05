package com.github.lechandonga.orderbookmatchingenginejava.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基于本地 JSONL 文件的只追加事件日志（event sourcing）。
 *
 * 文件格式（每行一条事件，UTF-8）：
 * <pre>
 * {"seq":1,"type":"ACCOUNT_OPENED","payload":{...},"checksum":"<sha256-hex>"}
 * </pre>
 * checksum = SHA-256(seq + "|" + type + "|" + canonical(payload))
 * canonical(payload) 为按 key 字典序递归排序、无多余空白的 JSON；
 * 空 payload 以 {} 表示。
 *
 * 兼容要求：
 * - 旧记录只允许新增可选字段；读取时忽略未知字段，但不得缺少既有必需字段。
 * - seq 必须从 1 起连续；checksum 必须逐字节匹配。
 * - 任何空行 / 非法 JSON / checksum 失败 / 字段类型错误都按损坏处理并抛出
 *   {@link LedgerCorruptedException}，绝不静默跳过。
 */
public class EventLog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EventLog.class);
    private static final HexFormat HEX = HexFormat.of();

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private long lastSeq;

    public EventLog(Path file) {
        this.file = file;
    }

    /** 读出全部事件并逐条校验；返回的列表保证序号连续、checksum 全部通过。 */
    public List<EventRecord> replay() {
        List<EventRecord> records = new ArrayList<>();
        if (!Files.exists(file)) {
            return records;
        }
        long expectedSeq = 1;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    throw new LedgerCorruptedException(
                            "事件记录第 " + lineNo + " 行为空（可能被截断）: " + file);
                }
                EventRecord record = parseLine(line, lineNo);
                if (record.seq() != expectedSeq) {
                    throw new LedgerCorruptedException(
                            "事件序号不连续：期望 " + expectedSeq + " 实际 " + record.seq()
                                    + "（第 " + lineNo + " 行，文件 " + file + "）");
                }
                if (!checksum(record.seq(), record.type(), record.payload()).equals(record.checksum())) {
                    throw new LedgerCorruptedException(
                            "事件 checksum 校验失败：seq=" + record.seq()
                                    + "（第 " + lineNo + " 行，记录可能已被篡改或损坏）");
                }
                records.add(record);
                expectedSeq++;
            }
        } catch (IOException e) {
            throw new LedgerCorruptedException("读取事件记录失败: " + file, e);
        }
        lastSeq = records.size();
        log.info("事件日志重放完成: file={}, 事件数={}", file, records.size());
        return records;
    }

    /** 追加一条事件（调用方持引擎锁）；先构造全部内容再追加写，保证单行原子性。 */
    public synchronized EventRecord append(EventType type, Map<String, Object> payload) {
        long seq = lastSeq + 1;
        Map<String, Object> safePayload = payload == null ? Map.of() : new LinkedHashMap<>(payload);
        String checksum = checksum(seq, type, safePayload);
        EventRecord record = new EventRecord(seq, type, java.util.Collections.unmodifiableMap(safePayload), checksum);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("seq", seq);
        envelope.put("type", type.name());
        envelope.put("payload", safePayload);
        envelope.put("checksum", checksum);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            String line = mapper.writeValueAsString(envelope) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("追加事件记录失败: " + file, e);
        }
        lastSeq = seq;
        return record;
    }

    public synchronized long lastSeq() {
        return lastSeq;
    }

    @SuppressWarnings("unchecked")
    private EventRecord parseLine(String line, int lineNo) {
        Map<String, Object> envelope;
        try {
            envelope = mapper.readValue(line, Map.class);
        } catch (IOException e) {
            throw new LedgerCorruptedException("事件 JSON 非法：第 " + lineNo + " 行", e);
        }
        Object seqObj = envelope.get("seq");
        Object typeObj = envelope.get("type");
        Object checksumObj = envelope.get("checksum");
        Object payloadObj = envelope.get("payload");
        if (!(seqObj instanceof Number) || !(typeObj instanceof String)
                || !(checksumObj instanceof String) || !(payloadObj instanceof Map)) {
            throw new LedgerCorruptedException(
                    "事件必需字段缺失或类型错误：第 " + lineNo + " 行");
        }
        EventType type;
        try {
            type = EventType.valueOf(((String) typeObj));
        } catch (IllegalArgumentException e) {
            throw new LedgerCorruptedException("未知事件类型 " + typeObj + "：第 " + lineNo + " 行", e);
        }
        return new EventRecord(((Number) seqObj).longValue(), type,
                new LinkedHashMap<>((Map<String, Object>) payloadObj), (String) checksumObj);
    }

    static String checksum(long seq, EventType type, Map<String, Object> payload) {
        try {
            String canonical = new ObjectMapper().writeValueAsString(new TreeMap<>(payload));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((seq + "|" + type.name() + "|" + canonical)
                    .getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hash);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("计算 checksum 失败", e);
        }
    }

    @Override
    public void close() {
        // 文件采用每次追加写，无需常驻句柄。
    }
}
