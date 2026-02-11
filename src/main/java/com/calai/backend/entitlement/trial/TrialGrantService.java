package com.calai.backend.entitlement.trial;

import com.calai.backend.common.crypto.HmacSha256;
import com.calai.backend.users.user.repo.UserRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TrialGrantService {

    private final UserTrialGrantRepository trialRepo;
    private final UserRepo userRepo;

    @Value("${app.trial.hashSecret:CHANGE_ME_IN_PROD}")
    private String hashSecret;

    @Transactional
    public void ensureTrialEligibleOrThrow(Long userId, String deviceId, Instant nowUtc) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new TrialNotEligibleException("DEVICE_ID_REQUIRED");
        }

        String email = userRepo.findById(userId)
                .orElseThrow(() -> new TrialNotEligibleException("USER_NOT_FOUND"))
                .getEmail();

        if (email == null || email.isBlank()) {
            throw new TrialNotEligibleException("EMAIL_REQUIRED");
        }

        String emailHash = HmacSha256.hex(hashSecret, email.trim().toLowerCase(Locale.ROOT));
        String deviceHash = HmacSha256.hex(hashSecret, deviceId.trim());

        // 已領過 → 擋
        if (trialRepo.findByEmailHash(emailHash).isPresent()) throw new TrialNotEligibleException("EMAIL_ALREADY_USED");
        if (trialRepo.findByDeviceHash(deviceHash).isPresent()) throw new TrialNotEligibleException("DEVICE_ALREADY_USED");

        try {
            UserTrialGrantEntity g = new UserTrialGrantEntity();
            g.setEmailHash(emailHash);
            g.setDeviceHash(deviceHash);
            g.setFirstUserId(userId);
            g.setGrantedAtUtc(nowUtc);
            trialRepo.save(g);
        } catch (DataIntegrityViolationException e) {
            // 競態：同時兩次請求
            throw new TrialNotEligibleException("TRIAL_ALREADY_USED");
        }
    }
}
