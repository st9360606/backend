package com.calai.backend.auth.service;

import com.calai.backend.auth.dto.AuthResponse;
import com.calai.backend.auth.dto.GoogleSignInExchangeRequest;
import com.calai.backend.auth.entity.AuthProvider;
import com.calai.backend.fasting.service.FastingPlanService;
import com.calai.backend.fasting.support.ClientTimeZoneResolver;
import com.calai.backend.users.user.entity.User;
import com.calai.backend.users.user.repo.UserRepo;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;

@Slf4j
@Service
public class GoogleAuthService {

    private final GoogleIdTokenVerifier verifier;
    private final UserRepo userRepo;
    private final TokenService tokenService;
    private final FastingPlanService fastingPlans;
    private final ClientTimeZoneResolver tzResolver;

    public GoogleAuthService(
            @Value("${app.google.web-client-id}") String webClientId,
            UserRepo userRepo,
            TokenService tokenService,
            FastingPlanService fastingPlans,
            ClientTimeZoneResolver tzResolver
    ) {
        this.userRepo = userRepo;
        this.tokenService = tokenService;
        this.verifier = new GoogleIdTokenVerifier
                .Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(webClientId))
                .build();
        this.fastingPlans = fastingPlans;
        this.tzResolver = tzResolver;
    }

    @Transactional
    public AuthResponse exchange(GoogleSignInExchangeRequest req, String deviceId, String ip, String ua) throws Exception {
        var idToken = verifier.verify(req.idToken());
        if (idToken == null) {
            throw new IllegalArgumentException("Invalid Google ID token");
        }

        var p = idToken.getPayload();
        final String sub = p.getSubject();
        final String email = String.valueOf(p.getEmail()).trim().toLowerCase();
        final String name = (String) p.get("name");
        final String picture = (String) p.get("picture");

        // 1) 先用 google_sub 找
        User user = userRepo.findByGoogleSub(sub).orElse(null);

        // 2) 找不到時，用 email 合併既有帳號（email 代表同一個人）
        if (user == null) {
            user = userRepo.findByEmailIgnoreCase(email).orElse(null);
        }

        // 3) 都沒有 → 建新帳號
        if (user == null) {
            user = new User();
            user.setEmail(email);
        }

        // 4) 回填/更新
        user.setGoogleSub(sub);
        user.setName(name);
        user.setPicture(picture);
        user.setProvider(AuthProvider.GOOGLE);
        user.setLastLoginAt(Instant.now());

        user = userRepo.save(user);

        // ✅ 不要在登入時預先建立 user_profiles
        //    否則新帳號會被前端誤判為已完成 onboarding。
        String clientTz = tzResolver.resolveFromCurrentRequest();
        fastingPlans.ensureDefaultIfMissing(user.getId(), clientTz);

        var pair = tokenService.issue(user, deviceId, ip, ua);
        return new AuthResponse(pair.accessToken(), pair.refreshToken());
    }
}
