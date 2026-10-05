package com.github.lechandonga.orderbookmatchingenginejava.api;

import com.github.lechandonga.orderbookmatchingenginejava.engine.CancelResult;
import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lechandonga.orderbookmatchingenginejava.engine.SubmitResult;
import com.github.lechandonga.orderbookmatchingenginejava.order.CancelRequest;
import com.github.lechandonga.orderbookmatchingenginejava.order.OrderRequest;
import org.springframework.stereotype.Service;

@Service
public class DefaultInternalTradingGateway implements InternalTradingGateway {

    private final MatchingEngine engine;

    public DefaultInternalTradingGateway(MatchingEngine engine) {
        this.engine = engine;
    }

    @Override
    public SubmitResult submitOrder(OrderRequest request) {
        return engine.submit(request);
    }

    @Override
    public CancelResult cancelOrder(CancelRequest request) {
        return engine.cancel(request);
    }
}
