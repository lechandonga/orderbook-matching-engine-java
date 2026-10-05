package com.github.lechandonga.orderbookmatchingenginejava.tests.support;

import com.github.lechandonga.orderbookmatchingenginejava.config.MatchingProperties;
import com.github.lechandonga.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lechandonga.orderbookmatchingenginejava.event.EventLog;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 构造隔离的引擎实例（临时事件文件 + 固定测试账户），不启动 Spring。
 */
public final class EngineTestSupport {

    public static final String AAPL = "AAPL";

    private EngineTestSupport() {
    }

    public static Path tempLedger() throws IOException {
        Path ledger = Files.createTempFile("matching-events-", ".jsonl");
        Files.writeString(ledger, "");
        ledger.toFile().deleteOnExit();
        return ledger;
    }

    public static MatchingProperties testProperties(Path ledger) {
        MatchingProperties properties = new MatchingProperties();
        properties.setLedgerPath(ledger.toString());
        properties.setMaxPrice(new BigDecimal("100000"));
        seed(properties, "alice", "100000", "100000", "100000", 100);
        seed(properties, "bob", "50000", "50000", "50000", 50);
        seed(properties, "carol", "1000", "1000", "100000", 0);
        // 并发争抢场景使用独立买家：每人只能买 1 股，避免同账户自成交干扰统计
        for (int i = 0; i < 100; i++) {
            seed(properties, "buyer" + i, "1000", "1000", "1000", 0);
        }
        return properties;
    }

    public static MatchingEngine newEngine(Path ledger) {
        MatchingEngine engine = new MatchingEngine(
                new EventLog(ledger), testProperties(ledger));
        engine.restore();
        return engine;
    }

    private static void seed(MatchingProperties properties, String id,
                             String cash, String maxCash, String maxExposure, long aapl) {
        MatchingProperties.SeedAccount account = new MatchingProperties.SeedAccount();
        account.setInitialCash(new BigDecimal(cash));
        account.setMaxCash(new BigDecimal(maxCash));
        account.setMaxExposure(new BigDecimal(maxExposure));
        account.getPositions().put(AAPL, aapl);
        properties.getSeedAccounts().put(id, account);
    }
}
