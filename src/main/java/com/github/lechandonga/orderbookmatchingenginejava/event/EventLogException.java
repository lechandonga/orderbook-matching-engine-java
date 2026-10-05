package com.github.lechandonga.orderbookmatchingenginejava.event;

/**
 * 事件记录缺失或损坏时抛出，启动与重放均失败，绝不静默跳过。
 */
public class EventLogException extends RuntimeException {
    public EventLogException(String message) {
        super(message);
    }

    public EventLogException(String message, Throwable cause) {
        super(message, cause);
    }
}
