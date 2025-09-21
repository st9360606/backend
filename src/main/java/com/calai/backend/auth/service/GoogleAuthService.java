package com.calai.backend.auth.service;

import com.calai.backend.auth.dto.AuthResponse;
import com.calai.backend.auth.dto.GoogleSignInExchangeRequest;
import com.calai.backend.auth.entity.User;
import com.calai.backend.auth.repo.UserRepo;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

@Slf4j
@Service
public class GoogleAuthService {

    private final GoogleIdTokenVerifier verifier;
    private final UserRepo userRepo;
    private final TokenService tokenService;

    public GoogleAuthService(
            @Value("${app.google.web-client-id}") String webClientId,
            UserRepo userRepo,
            TokenService tokenService
    ) {
        this.userRepo = userRepo;
        this.tokenService = tokenService;
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(webClientId))
                .build();
    }

    @Transactional
    public AuthResponse exchange(GoogleSignInExchangeRequest req, String deviceId, String ip, String ua) throws Exception {
        var idToken = verifier.verify(req.idToken());
        if (idToken == null) throw new IllegalArgumentException("Invalid Google ID token");

        var p = idToken.getPayload();
        String sub = p.getSubject();
        String email = (String) p.getEmail();
        String name = (String) p.get("name");
        String picture = (String) p.get("picture");

        // upsert user
        var user = userRepo.findByGoogleSub(sub).orElseGet(() -> {
            var u = new User();
            u.setGoogleSub(sub);
            return u;
        });
        user.setEmail(email);
        user.setName(name);
        user.setPicture(picture);
        user.setLastLoginAt(Instant.now());
        user = userRepo.save(user);

        // 簽發 & 寫入 DB（這一步會新增 auth_tokens 兩筆）
        var pair = tokenService.issue(user, deviceId, ip, ua);

        return new AuthResponse(pair.accessToken(), pair.refreshToken());
    }
}

