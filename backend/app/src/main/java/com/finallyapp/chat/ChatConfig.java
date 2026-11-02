package com.finallyapp.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finallyapp.config.FinallyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ChatConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatConfig.class);

    @Bean
    LlmClient llmClient(FinallyProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        if (props.llm().mock()) {
            log.info("LLM: deterministic mock (LLM_MOCK=true)");
            return new MockLlmClient(mapper);
        }
        log.info("LLM: OpenRouter {} via {}", props.llm().model(), props.llm().provider());
        return new OpenRouterLlmClient(builder, props.llm(), mapper);
    }

    @Bean
    LlmResponseParser llmResponseParser(ObjectMapper mapper) {
        return new LlmResponseParser(mapper);
    }
}
