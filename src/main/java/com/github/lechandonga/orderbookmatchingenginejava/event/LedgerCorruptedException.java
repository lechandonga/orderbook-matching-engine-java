package com.github.lechandonga.orderbookmatchingenginejava.event;

/**
 * 事件记录缺失（序号不连续 / 文件被截断）或损坏（JSON 非法 / checksum 不匹配 /
 * 字段非法）时抛出。调用方必须让启动失败，严禁静默跳过。
 */
public class LedgerCorruptedException extends RuntimeException {
    public LedgerCorruptedException(String message) {
        super(message);
    }

    public LedgerCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
