package com.finallyapp.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finallyapp.config.FinallyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Chooses the market data source: Massive when {@code MASSIVE_API_KEY} is set, otherwise the simulator. */
@Configuration
public class MarketDataConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketDataConfig.class);

    @Bean(destroyMethod = "stop")
    MarketDataSource marketDataSource(FinallyProperties props, PriceCache cache,
                                      RestClient.Builder builder, ObjectMapper mapper) {
        return create(props.market(), cache, builder, mapper);
    }

    public static MarketDataSource create(FinallyProperties.Market cfg, PriceCache cache,
                                          RestClient.Builder builder, ObjectMapper mapper) {
        if (cfg.useMassive()) {
            log.info("Market data source: Massive API (real data)");
            return new MassiveMarketDataSource(cache, builder, cfg.massiveBaseUrl(), cfg.massiveApiKey().trim(),
                    cfg.massivePollInterval(), mapper);
        }
        log.info("Market data source: GBM simulator");
        return new SimulatorMarketDataSource(cache, cfg.simulatorInterval(), cfg.eventProbability());
    }
}
