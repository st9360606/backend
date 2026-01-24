package com.calai.backend.accountdelete.service;

import com.calai.backend.accountdelete.entity.AccountDeletionRequestEntity;
import com.calai.backend.accountdelete.repo.AccountDeletionRequestRepository;
import com.calai.backend.auth.repo.AuthTokenRepo;
import com.calai.backend.users.user.entity.User;
import com.calai.backend.users.user.repo.UserRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@RequiredArgsConstructor
@Service
public class AccountDeletionService {

    private final AccountDeletionRequestRepository reqRepo;
    private final UserRepo userRepo;
    private final AuthTokenRepo authTokenRepo;

    @Transactional
    public void requestDeletion(Long userId) {
        Instant now = Instant.now();

        // 1) upsert deletion request（冪等）
        var req = reqRepo.findByUserIdForUpdate(userId).orElseGet(AccountDeletionRequestEntity::new);
        req.setUserId(userId);
        req.setReqStatus("REQUESTED");
        req.setRequestedAtUtc(now);
        reqRepo.save(req);

        // 2) 立即鎖帳 + 去識別（允許 email 回來新註冊）
        User u = userRepo.findByIdForUpdate(userId);
        if (u == null) throw new IllegalArgumentException("USER_NOT_FOUND");

        String email = u.getEmail();
        if (email != null && !email.isBlank()) {
            u.setDeletedEmailHash(sha256Hex(email.toLowerCase()));
        }

        u.setStatus("DELETING");
        u.setDeletedAtUtc(now);

        // ✅ 關鍵：清空 email/google_sub/name/picture
        u.setEmail(null);
        u.setGoogleSub(null);
        u.setName(null);
        u.setPicture(null);

        userRepo.save(u);

        // 3) revoke tokens（強制登出）
        authTokenRepo.revokeAllByUserId(userId, now);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            return null;
        }
    }
}
