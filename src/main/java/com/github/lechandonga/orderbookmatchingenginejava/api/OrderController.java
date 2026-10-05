package com.github.lechandonga.orderbookmatchingenginejava.api;

import com.github.lechandonga.orderbookmatchingenginejava.domain.Side;
import com.github.lechandonga.orderbookmatchingenginejava.engine.AccountSnapshot;
import com.github.lechandonga.orderbookmatchingenginejava.engine.CancelResult;
import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lechandonga.orderbookmatchingenginejava.engine.SubmitResult;
import com.github.lechandonga.orderbookmatchingenginejava.order.CancelRequest;
import com.github.lechandonga.orderbookmatchingenginejava.order.Order;
import com.github.lechandonga.orderbookmatchingenginejava.order.OrderRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对外 HTTP 入口。请求体与内部调用入口使用同一套 DTO，
 * 拒绝原因通过 {@link ApiExceptionHandler} 映射为区分化的 4xx 响应。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final MatchingEngine engine;

    public OrderController(MatchingEngine engine) {
        this.engine = engine;
    }

    @PostMapping
    public SubmitResult submit(@RequestBody OrderRequest request) {
        SubmitResult result = engine.submit(request);
        if (!result.accepted()) {
            throw new OrderRejectedException(result.rejectReason(), result.rejectMessage());
        }
        return result;
    }

    @PostMapping("/cancel")
    public CancelResult cancel(@RequestBody CancelRequest request) {
        CancelResult result = engine.cancel(request);
        if (!result.success()) {
            throw new OrderRejectedException(result.rejectReason(), result.rejectMessage());
        }
        return result;
    }

    @GetMapping("/{clientOrderId}")
    public ResponseEntity<Map<String, Object>> query(@PathVariable String clientOrderId) {
        Order order = engine.getOrder(clientOrderId);
        if (order == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("clientOrderId", clientOrderId, "found", false));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("clientOrderId", order.clientOrderId());
        view.put("accountId", order.accountId());
        view.put("symbol", order.symbol());
        view.put("side", order.side());
        view.put("type", order.type());
        view.put("price", order.type().name().equals("LIMIT") ? order.displayPrice() : null);
        view.put("originalQuantity", order.originalQty());
        view.put("filledQuantity", order.filledQty());
        view.put("remainingQuantity", order.remainingQty());
        view.put("status", order.status());
        return ResponseEntity.ok(view);
    }

    @GetMapping("/accounts/{accountId}")
    public AccountSnapshot account(@PathVariable String accountId) {
        AccountSnapshot snapshot = engine.snapshotAccount(accountId);
        if (snapshot == null) {
            throw new OrderRejectedException(
                    com.github.lechandonga.orderbookmatchingenginejava.domain.RejectReason.UNKNOWN_ACCOUNT,
                    "账户不存在: " + accountId);
        }
        return snapshot;
    }

    @GetMapping("/books/{symbol}/depth")
    public Map<String, Integer> depth(@PathVariable String symbol) {
        return Map.of("bidDepth", engine.bookDepth(symbol, Side.BUY),
                "askDepth", engine.bookDepth(symbol, Side.SELL));
    }
}
