package com.github.lechandonga.orderbookmatchingenginejava.api;

import com.github.lechandonga.orderbookmatchingenginejava.engine.CancelResult;
import com.github.lechandonga.orderbookmatchingenginejava.engine.SubmitResult;
import com.github.lechandonga.orderbookmatchingenginejava.order.CancelRequest;
import com.github.lechandonga.orderbookmatchingenginejava.order.OrderRequest;

/**
 * 进程内服务之间调用下单 / 撤单的入口。
 * 与 HTTP Controller 一样直接委托 MatchingEngine，保证两处入口判定完全一致。
 */
public interface InternalTradingGateway {

    SubmitResult submitOrder(OrderRequest request);

    CancelResult cancelOrder(CancelRequest request);
}
