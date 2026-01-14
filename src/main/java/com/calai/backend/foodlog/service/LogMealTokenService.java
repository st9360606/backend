package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.crypto.AesGcmCrypto;
import com.calai.backend.foodlog.entity.LogMealAccountEntity;
import com.calai.backend.foodlog.repo.LogMealAccountRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.foodlog.provider", havingValue = "LOGMEAL")
public class LogMealTokenService {

    private final LogMealAccountRepository repo;
    private final AesGcmCrypto crypto;

    public LogMealTokenService(LogMealAccountRepository repo, AesGcmCrypto crypto) {
        this.repo = repo;
        this.crypto = crypto;
    }

    @Transactional(readOnly = true)
    public String requireApiUserToken(Long userId) {
        var e = repo.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("LOGMEAL_TOKEN_MISSING"));
        if (!"ACTIVE".equalsIgnoreCase(e.getStatus())) {
            throw new IllegalStateException("LOGMEAL_TOKEN_DISABLED");
        }
        return crypto.decryptFromBase64(e.getApiUserTokenEnc());
    }

    @Transactional
    public void upsertToken(Long userId, String plainToken) {
        var e = repo.findByUserId(userId).orElseGet(LogMealAccountEntity::new);
        e.setUserId(userId);
        e.setApiUserTokenEnc(crypto.encryptToBase64(plainToken));
        e.setStatus("ACTIVE");
        repo.save(e);
    }
}
