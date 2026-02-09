package com.calai.backend.foodlog.barcode;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean("offRestClient")
    public RestClient offRestClient() {
        return RestClient.builder().build();
    }
}
