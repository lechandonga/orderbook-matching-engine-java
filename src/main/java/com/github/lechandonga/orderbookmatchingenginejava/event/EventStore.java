package com.github.lechandonga.orderbookmatchingenginejava.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 本地追加型事件记录（JSON Lines）。
 * 启动时全量读取并做严格校验：文件缺失视为空历史；内容损坏抛 {@link EventLogException}。
 */
@Component
public class EventStore {
    private static final Logger log = LoggerFactory.getLogger(EventStore.class);
    public static final int FORMAT_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final Path file;

    public EventStore(ObjectMapper objectMapper,
                      @Value("${matching.event-log:./data/event-log.jsonl}") String eventLogPath) {
        this.objectMapper = objectMapper;
        this.file = Path.of(eventLogPath);
    }

    @PostConstruct
    void init() throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
    }

    /** 读取并严格校验全部事件；任何损坏立即失败。 */
    public List<EventRecord> readAll() {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            java.util.List<EventRecord> records = new java.util.ArrayList<>();
            String line;
            long lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    throw new EventLogException("事件记录第 " + lineNo + " 行为空，记录可能缺失或损坏");
                }
                EventRecord record = parse(line, lineNo);
                long expectedSeq = records.size() + 1L;
                if (record.seq() != expectedSeq) {
                    throw new EventLogException("事件记录第 " + lineNo + " 行 seq=" + record.seq()
                            + "，期望 " + expectedSeq + "（存在缺失或乱序）");
                }
                records.add(record);
            }
            return List.copyOf(records);
        } catch (IOException e) {
            throw new EventLogException("无法读取事件记录文件: " + file, e);
        }
    }

    private EventRecord parse(String line, long lineNo) {
        EventRecord record;
        try {
            record = objectMapper.readValue(line, EventRecord.class);
        } catch (IOException e) {
            throw new EventLogException("事件记录第 " + lineNo + " 行不是合法 JSON", e);
        }
        if (record.version() != FORMAT_VERSION) {
            throw new EventLogException("事件记录第 " + lineNo + " 行 version=" + record.version()
                    + " 不受支持，仅支持 " + FORMAT_VERSION);
        }
        if (record.type() == null || record.payload() == null) {
            throw new EventLogException("事件记录第 " + lineNo + " 行缺少 type/payload 字段");
        }
        String expectedCrc = checksum(canonicalPayload(record));
        if (!expectedCrc.equalsIgnoreCase(record.crc())) {
            throw new EventLogException("事件记录第 " + lineNo + " 行 CRC 校验失败，内容已损坏");
        }
        return record;
    }

    /** 追加事件（带 CRC），并落盘，保证重启后可恢复。调用方需持有全局串行锁。 */
    public synchronized void append(EventRecord record) {
        if (record.seq() < 1) {
            throw new IllegalArgumentException("事件 seq 必须从 1 开始");
        }
        String payloadJson = canonicalPayload(record);
        EventRecord withCrc = new EventRecord(record.version(), record.seq(), record.type(),
                record.payload(), checksum(payloadJson));
        try {
            String line = objectMapper.writeValueAsString(withCrc) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("追加事件记录失败: " + file, e);
        }
    }

    private String canonicalPayload(EventRecord record) {
        try {
            return objectMapper.writeValueAsString(record.payload());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String checksum(String content) {
        CRC32 crc = new CRC32();
        crc.update(content.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().toHexDigits(crc.getValue());
    }

    public Path getFile() {
        return file;
    }
}
