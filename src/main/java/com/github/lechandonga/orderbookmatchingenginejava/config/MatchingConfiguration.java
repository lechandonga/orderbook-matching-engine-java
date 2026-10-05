package com.github.lechandonga.orderbookmatchingenginejava.config;

import com.github.lechandonga.orderbookmatchingenginejava.event.EventLog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(MatchingProperties.class)
public class MatchingConfiguration {

    @Bean(destroyMethod = "close")
    public EventLog eventLog(MatchingProperties properties) {
        return new EventLog(Path.of(properties.getLedgerPath()));
    }
}
