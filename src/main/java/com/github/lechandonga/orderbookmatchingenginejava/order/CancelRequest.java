package com.github.lechandonga.orderbookmatchingenginejava.order;

/**
 * 撤单请求。
 */
public record CancelRequest(
        String clientOrderId,
        String accountId
) {
}
