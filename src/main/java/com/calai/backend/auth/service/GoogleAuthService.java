package com.calai.backend.auth.service;

import com.calai.backend.auth.dto.AuthResponse;
import com.calai.backend.auth.dto.GoogleSignInExchangeRequest;
import com.calai.backend.auth.entity.Provider;
import com.calai.backend.auth.entity.User;
import com.calai.backend.auth.repo.UserRepo;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;

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

        var user = userRepo.findByGoogleSub(sub).orElseGet(() -> {
            var u = new User();
            u.setGoogleSub(sub);
            // ★ 新增：GOOGLE 登入新建就標記 provider / verified
            u.setProvider(Provider.GOOGLE);
            u.setEmailVerified(Boolean.TRUE);
            return u;
        });

        // ★ 若早期資料沒寫 provider/verified，這裡補上（但不覆蓋其他 provider）
        if (user.getProvider() == null) user.setProvider(Provider.GOOGLE);
        if (user.getEmailVerified() == null || !user.getEmailVerified()) user.setEmailVerified(Boolean.TRUE);

        user.setEmail(email);
        user.setName(name);
        user.setPicture(picture);
        user.setLastLoginAt(Instant.now());
        user = userRepo.save(user);

        var pair = tokenService.issue(user, deviceId, ip, ua);
        return new AuthResponse(pair.accessToken(), pair.refreshToken());
    }
}
