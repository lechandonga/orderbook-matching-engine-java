package com.github.lechandonga.orderbookmatchingenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.lechandonga.orderbookmatchingenginejava.api.SubmitOrderRequest;
import com.github.lechandonga.orderbookmatchingenginejava.config.MatchingProperties;
import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingService;
import com.github.lechandonga.orderbookmatchingenginejava.event.EventStore;
import com.github.lechandonga.orderbookmatchingenginejava.ledger.Account;
import com.github.lechandonga.orderbookmatchingenginejava.ledger.AccountRepository;
import com.github.lechandonga.orderbookmatchingenginejava.model.OrderType;
import com.github.lechandonga.orderbookmatchingenginejava.model.Side;
import com.github.lechandonga.orderbookmatchingenginejava.model.TimeInForce;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

final class TestSupport {
    static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    private TestSupport() {
    }

    static MatchingProperties properties() {
        MatchingProperties properties = new MatchingProperties();
        properties.setSymbols(java.util.List.of("BTCUSD"));
        properties.setMarketProtectionPrice(Map.of("BTCUSD", new BigDecimal("100")));
        MatchingProperties.AccountSeed buyer = new MatchingProperties.AccountSeed();
        buyer.setCash(new BigDecimal("100000"));
        buyer.setExposureLimit(new BigDecimal("100000"));
        MatchingProperties.AccountSeed seller = new MatchingProperties.AccountSeed();
        seller.setCash(new BigDecimal("100000"));
        seller.setExposureLimit(new BigDecimal("100000"));
        seller.setPositions(Map.of("BTCUSD", 100L));
        MatchingProperties.AccountSeed poor = new MatchingProperties.AccountSeed();
        poor.setCash(new BigDecimal("5"));
        poor.setExposureLimit(new BigDecimal("5"));
        poor.setPositions(Map.of("BTCUSD", 1L));
        Map<String, MatchingProperties.AccountSeed> accounts = new HashMap<>();
        accounts.put("buyer", buyer);
        accounts.put("seller", seller);
        accounts.put("poor", poor);
        properties.setAccounts(accounts);
        return properties;
    }

    static ObjectMapper objectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }

    static EventStore eventStore(Path path) {
        EventStore store = new EventStore(objectMapper(), path.toString());
        try {
            store.init();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return store;
    }

    static MatchingService service(Path path, MatchingProperties properties, Instant now) {
        return new MatchingService(eventStore(path), new AccountRepository(properties), properties,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    static MatchingService recovered(Path path, MatchingProperties properties, Instant now) {
        MatchingService service = service(path, properties, now);
        service.recover();
        return service;
    }

    static SubmitOrderRequest order(String id, String account, Side side, OrderType type,
                                    String price, long qty) {
        return order(id, account, side, type, price, qty, null, null);
    }

    static SubmitOrderRequest order(String id, String account, Side side, OrderType type,
                                    String price, long qty, TimeInForce tif, Instant expiresAt) {
        SubmitOrderRequest request = new SubmitOrderRequest();
        request.setClientOrderId(id);
        request.setAccountId(account);
        request.setSymbol("BTCUSD");
        request.setSide(side);
        request.setType(type);
        request.setPrice(price == null ? null : new BigDecimal(price));
        request.setQty(qty);
        request.setTimeInForce(tif);
        request.setExpiresAt(expiresAt);
        return request;
    }

    static Account account(MatchingService service, String accountId) {
        return service.accounts().stream()
                .filter(account -> account.getAccountId().equals(accountId))
                .findFirst()
                .orElseThrow();
    }
}
