package com.calai.backend.auth.service;

import com.calai.backend.auth.dto.AuthResponse;
import com.calai.backend.auth.dto.GoogleSignInExchangeRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;

@Slf4j
@Service
public class GoogleAuthService {

    private final String webClientId;
    private final GoogleIdTokenVerifier verifier;

    public GoogleAuthService(@Value("${app.google.web-client-id}") String webClientId) {
        this.webClientId = webClientId;
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(webClientId))
                .build();
    }
    public AuthResponse exchange(GoogleSignInExchangeRequest req) throws Exception {
        log.info("GoogleSignInExchangeRequest(idToken={}, clientId={})", req.idToken(), req.clientId());
        // 僅使用伺服器端設定的 webClientId 驗證 audience；忽略 req.clientId（避免被偽造）
        GoogleIdToken idToken = verifier.verify(req.idToken());
        if (idToken == null) {
            throw new IllegalArgumentException("Invalid Google ID token");
        }


        Payload payload = idToken.getPayload();

        // 安全附加檢查（建議）
        String issuer = (String) payload.getIssuer();
        if (!"accounts.google.com".equals(issuer) && !"https://accounts.google.com".equals(issuer)) {
            throw new IllegalArgumentException("Invalid issuer: " + issuer);
        }

        String sub = payload.getSubject();           // Google 唯一使用者 ID
        String email = (String) payload.getEmail();  // 使用者 email（可能為 null，視 scope）

        // TODO: 在這裡查/建會員，簽發你系統的 access/refresh token
        String access = "access-" + UUID.randomUUID();
        String refresh = "refresh-" + UUID.randomUUID();

        return new AuthResponse(access, refresh);
    }
}
