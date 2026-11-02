package com.finallyapp.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Application settings under the {@code finally.*} prefix (see application.yml). */
@ConfigurationProperties(prefix = "finally")
public record FinallyProperties(
        @DefaultValue Llm llm,
        @DefaultValue Market market) {

    public record Llm(
            @DefaultValue("false") boolean mock,
            @DefaultValue("") String apiKey,
            @DefaultValue("https://openrouter.ai/api/v1") String baseUrl,
            @DefaultValue("openai/gpt-oss-120b") String model,
            @DefaultValue("cerebras") String provider,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("20") int historyLimit) {
    }

    public record Market(
            @DefaultValue("") String massiveApiKey,
            @DefaultValue("https://api.massive.com") String massiveBaseUrl,
            @DefaultValue("15s") Duration massivePollInterval,
            @DefaultValue("500ms") Duration simulatorInterval,
            @DefaultValue("0.001") double eventProbability) {

        public boolean useMassive() {
            return massiveApiKey != null && !massiveApiKey.isBlank();
        }
    }
}
