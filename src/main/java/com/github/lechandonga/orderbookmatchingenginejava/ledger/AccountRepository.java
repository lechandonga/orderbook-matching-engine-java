package com.github.lechandonga.orderbookmatchingenginejava.ledger;

import com.github.lechandonga.orderbookmatchingenginejava.config.MatchingProperties;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AccountRepository {
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    public AccountRepository(MatchingProperties properties) {
        properties.getAccounts().forEach((id, seed) -> {
            Account account = new Account(id, seed.getCash(), seed.getExposureLimit());
            seed.getPositions().forEach(account::seedPosition);
            accounts.put(id, account);
        });
    }

    public Optional<Account> find(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public Collection<Account> all() {
        return accounts.values();
    }
}
