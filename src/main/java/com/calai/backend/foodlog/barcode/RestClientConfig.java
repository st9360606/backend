package com.calai.backend.foodlog.barcode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean("offRestClient")
    public RestClient offRestClient(
            @Value("${app.openfoodfacts.base-url:https://world.openfoodfacts.org}") String baseUrl,
            @Value("${app.openfoodfacts.connect-timeout:PT3S}") Duration connectTimeout,
            @Value("${app.openfoodfacts.read-timeout:PT5S}") Duration readTimeout,
            @Value("${app.openfoodfacts.user-agent:BitCal_AI/1.0 (contact: dev@bitcalai.example)}") String userAgent
    ) {
        HttpClient hc = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();

        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(hc);
        rf.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .build();
    }
}
