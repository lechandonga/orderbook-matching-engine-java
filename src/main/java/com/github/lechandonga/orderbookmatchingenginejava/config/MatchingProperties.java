package com.github.lechandonga.orderbookmatchingenginejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "matching")
public class MatchingProperties {
    private List<String> symbols = List.of("BTCUSD");
    /** 各品种市价单风控预留的最差价格（保护价）。 */
    private Map<String, BigDecimal> marketProtectionPrice = new LinkedHashMap<>();
    private Map<String, AccountSeed> accounts = new LinkedHashMap<>();

    public List<String> getSymbols() { return symbols; }
    public void setSymbols(List<String> symbols) { this.symbols = symbols; }
    public Map<String, BigDecimal> getMarketProtectionPrice() { return marketProtectionPrice; }
    public void setMarketProtectionPrice(Map<String, BigDecimal> marketProtectionPrice) {
        this.marketProtectionPrice = marketProtectionPrice;
    }
    public Map<String, AccountSeed> getAccounts() { return accounts; }
    public void setAccounts(Map<String, AccountSeed> accounts) { this.accounts = accounts; }

    public static class AccountSeed {
        private BigDecimal cash = BigDecimal.valueOf(1_000_000);
        /** 买入在途敞口（冻结资金）上限。 */
        private BigDecimal exposureLimit = BigDecimal.valueOf(500_000);
        /** 初始持仓：symbol -> qty。 */
        private Map<String, Long> positions = new LinkedHashMap<>();

        public BigDecimal getCash() { return cash; }
        public void setCash(BigDecimal cash) { this.cash = cash; }
        public BigDecimal getExposureLimit() { return exposureLimit; }
        public void setExposureLimit(BigDecimal exposureLimit) { this.exposureLimit = exposureLimit; }
        public Map<String, Long> getPositions() { return positions; }
        public void setPositions(Map<String, Long> positions) { this.positions = positions; }
    }
}
