package com.github.lechandonga.orderbookmatchingenginejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 撮合引擎可配置项（application.properties 中以 matching.* 覆盖）。
 *
 * ledger-path           事件日志文件（JSONL）
 * max-price             价格上限（展示单位）
 * min-order-quantity    最小下单数量
 * max-order-quantity    最大下单数量
 * market-protection     市价单风控预留名义金额所使用的保护参考价：
 *                       买入按对手卖一价、卖出按对手买一价；簿上无报价时市价单拒绝
 * seed-accounts         全新日志首次启动时播种的账户（id -> 初始现金/额度/敞口）
 */
@ConfigurationProperties(prefix = "matching")
public class MatchingProperties {

    private String ledgerPath = "data/events.jsonl";
    private BigDecimal maxPrice = new BigDecimal("1000000");
    private long minOrderQuantity = 1L;
    private long maxOrderQuantity = 1_000_000_000L;
    private final Map<String, SeedAccount> seedAccounts = new LinkedHashMap<>();

    public String getLedgerPath() {
        return ledgerPath;
    }

    public void setLedgerPath(String ledgerPath) {
        this.ledgerPath = ledgerPath;
    }

    public BigDecimal getMaxPrice() {
        return maxPrice;
    }

    public void setMaxPrice(BigDecimal maxPrice) {
        this.maxPrice = maxPrice;
    }

    public long getMinOrderQuantity() {
        return minOrderQuantity;
    }

    public void setMinOrderQuantity(long minOrderQuantity) {
        this.minOrderQuantity = minOrderQuantity;
    }

    public long getMaxOrderQuantity() {
        return maxOrderQuantity;
    }

    public void setMaxOrderQuantity(long maxOrderQuantity) {
        this.maxOrderQuantity = maxOrderQuantity;
    }

    public Map<String, SeedAccount> getSeedAccounts() {
        return seedAccounts;
    }

    public static class SeedAccount {
        private BigDecimal initialCash = BigDecimal.ZERO;
        private BigDecimal maxCash = BigDecimal.ZERO;
        private BigDecimal maxExposure = BigDecimal.ZERO;
        private Map<String, Long> positions = new LinkedHashMap<>();

        public BigDecimal getInitialCash() {
            return initialCash;
        }

        public void setInitialCash(BigDecimal initialCash) {
            this.initialCash = initialCash;
        }

        public BigDecimal getMaxCash() {
            return maxCash;
        }

        public void setMaxCash(BigDecimal maxCash) {
            this.maxCash = maxCash;
        }

        public BigDecimal getMaxExposure() {
            return maxExposure;
        }

        public void setMaxExposure(BigDecimal maxExposure) {
            this.maxExposure = maxExposure;
        }

        public Map<String, Long> getPositions() {
            return positions;
        }

        public void setPositions(Map<String, Long> positions) {
            this.positions = positions;
        }
    }
}
