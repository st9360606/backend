package com.calai.backend.foodlog.barcode.openfoodfacts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Configuration
public class OpenFoodFactsRestClientConfig {

    @Bean("offRestClient")
    public RestClient offRestClient(
            @Value("${app.openfoodfacts.base-url:https://world.openfoodfacts.org}") String baseUrl,
            @Value("${app.openfoodfacts.connect-timeout:PT3S}") Duration connectTimeout,
            @Value("${app.openfoodfacts.read-timeout:PT5S}") Duration readTimeout,
            @Value("${app.openfoodfacts.user-agent:BitCal_AI/1.0 (contact: dev@bitcalai.example)}") String userAgent,

            // ✅ staging 用（.net 需要 basic auth）；production 留空即可
            @Value("${app.openfoodfacts.basic-auth-user:}") String basicUser,
            @Value("${app.openfoodfacts.basic-auth-pass:}") String basicPass
    ) {
        HttpClient hc = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();

        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(hc);
        rf.setReadTimeout(readTimeout);

        RestClient.Builder b = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent);

        // ✅ 可選：只有設定了才加
        if (basicUser != null && !basicUser.isBlank() && basicPass != null && !basicPass.isBlank()) {
            String token = basicUser.trim() + ":" + basicPass;
            String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
        }

        return b.build();
    }
}
