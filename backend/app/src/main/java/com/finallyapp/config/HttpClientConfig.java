package com.finallyapp.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    /** Builder for outbound HTTP (OpenRouter, Massive), with sane timeouts. Prototype: callers customize it. */
    @Bean
    @Scope("prototype")
    RestClient.Builder outboundRestClientBuilder(FinallyProperties props) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.llm().timeout());
        return RestClient.builder().requestFactory(factory);
    }
}
