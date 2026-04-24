package com.calai.backend.referral.service;

import com.calai.backend.referral.entity.UserReferralCodeEntity;
import com.calai.backend.referral.repo.UserReferralCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Locale;

@RequiredArgsConstructor
@Service
public class ReferralCodeService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private final SecureRandom random = new SecureRandom();
    private final UserReferralCodeRepository codeRepository;

    @Transactional
    public String getOrCreateCode(Long userId) {
        return codeRepository.findByUserId(userId)
                .map(UserReferralCodeEntity::getPromoCode)
                .orElseGet(() -> createCode(userId));
    }

    public Long findInviterByPromoCode(String promoCode) {
        return codeRepository.findByPromoCodeAndActiveIsTrue(normalizePromoCode(promoCode))
                .map(UserReferralCodeEntity::getUserId)
                .orElse(null);
    }

    public static String normalizePromoCode(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private String createCode(Long userId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = nextCode();
            if (codeRepository.findByPromoCodeAndActiveIsTrue(code).isEmpty()) {
                UserReferralCodeEntity entity = new UserReferralCodeEntity();
                entity.setUserId(userId);
                entity.setPromoCode(code);
                codeRepository.save(entity);
                return code;
            }
        }
        throw new IllegalStateException("REFERRAL_CODE_GENERATION_FAILED");
    }

    private String nextCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
