package com.github.lechandonga.orderbookmatchingenginejava.event;

import java.util.Map;

/**
 * 一条不可变事件记录。
 *
 * @param seq      全局单调递增序号，从 1 开始；重放时必须连续
 * @param type     事件类型
 * @param payload  事件字段（仅允许 String / Long / Boolean / null，保证 JSON 规范序列化）
 * @param checksum 对 "seq|type|canonicalJson(payload)" 计算的 SHA-256（64 位十六进制）
 */
public record EventRecord(
        long seq,
        EventType type,
        Map<String, Object> payload,
        String checksum
) {
    public String asPayloadString(String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    public long asPayloadLong(String key) {
        Object value = payload.get(key);
        return value == null ? 0L : ((Number) value).longValue();
    }
}
