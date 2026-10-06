package com.github.lechandonga.orderbookmatchingenginejava.api;

import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingService;
import com.github.lechandonga.orderbookmatchingenginejava.model.Order;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class OrderController {
    private final MatchingService matchingService;

    public OrderController(MatchingService matchingService) {
        this.matchingService = matchingService;
    }

    @PostMapping("/orders")
    public OrderResult submit(@RequestBody SubmitOrderRequest request) {
        return matchingService.submit(request);
    }

    @PostMapping("/orders/{clientOrderId}/cancel")
    public OrderResult cancel(@PathVariable String clientOrderId, @RequestParam String accountId) {
        return matchingService.cancel(clientOrderId, accountId);
    }

    @GetMapping("/orders/{clientOrderId}")
    public OrderView get(@PathVariable String clientOrderId) {
        Order order = matchingService.order(clientOrderId);
        return order == null ? null : OrderView.of(order);
    }

    @PostMapping("/maintenance/expire")
    public List<OrderResult> expire(@RequestParam(required = false) Instant now) {
        return matchingService.expireDue(now == null ? Instant.now() : now);
    }
}
