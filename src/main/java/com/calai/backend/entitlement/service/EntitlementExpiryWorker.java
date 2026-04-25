package com.calai.backend.entitlement.service;

import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class EntitlementExpiryWorker {

    private final UserEntitlementRepository entitlementRepository;

    /**
     * 每 10 分鐘把已過期的 ACTIVE entitlement 標成 EXPIRED。
     *
     * 注意：
     * 這不是 Trial 變 Free 的必要條件。
     * API 早就會透過 validToUtc > now 判斷是否有效。
     * 這個 worker 主要是為了資料一致性、客服查詢、後台查帳。
     */
    @Scheduled(fixedDelayString = "${app.entitlement.expiry-worker.fixed-delay:PT10M}")
    @Transactional
    public void expireEndedEntitlements() {
        Instant now = Instant.now();
        int affected = entitlementRepository.expireAllEndedEntitlements(now);

        if (affected > 0) {
            log.info("expired_ended_entitlements count={} now={}", affected, now);
        }
    }
}
