package com.calai.backend.referral.service;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.referral.entity.MembershipRewardLedgerEntity;
import com.calai.backend.referral.repo.MembershipRewardLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@RequiredArgsConstructor
@Service
public class MembershipRewardAttemptTxService {

    private final MembershipRewardLedgerRepository ledgerRepository;
    private final UserEntitlementRepository entitlementRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MembershipRewardLedgerEntity saveNewAttempt(MembershipRewardLedgerEntity ledger) {
        return ledgerRepository.saveAndFlush(ledger);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markGoogleDeferSuccess(
            Long ledgerId,
            Instant newPremiumUntil,
            String requestJson,
            String responseJson,
            Integer httpStatus,
            Instant now
    ) {
        MembershipRewardLedgerEntity ledger = ledgerRepository.findById(ledgerId)
                .orElseThrow(() -> new IllegalStateException("REWARD_LEDGER_NOT_FOUND: " + ledgerId));

        ledger.setGrantStatus("SUCCESS");
        ledger.setGoogleDeferStatus("SUCCESS");
        ledger.setGoogleDeferRequestJson(requestJson);
        ledger.setGoogleDeferResponseJson(responseJson);
        ledger.setGoogleDeferHttpStatus(httpStatus);
        ledger.setNewPremiumUntil(newPremiumUntil);
        ledger.setNextRetryAtUtc(null);
        ledger.setErrorCode(null);
        ledger.setErrorMessage(null);
        ledger.setGrantedAtUtc(now);

        ledgerRepository.saveAndFlush(ledger);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markGoogleDeferFailure(
            Long ledgerId,
            String grantStatus,
            String googleDeferStatus,
            String requestJson,
            String responseJson,
            Integer httpStatus,
            String errorCode,
            String errorMessage,
            Instant nextRetryAtUtc,
            Instant now
    ) {
        MembershipRewardLedgerEntity ledger = ledgerRepository.findById(ledgerId)
                .orElseThrow(() -> new IllegalStateException("REWARD_LEDGER_NOT_FOUND: " + ledgerId));

        ledger.setGrantStatus(grantStatus);
        ledger.setGoogleDeferStatus(googleDeferStatus);
        ledger.setGoogleDeferRequestJson(requestJson);
        ledger.setGoogleDeferResponseJson(responseJson);
        ledger.setGoogleDeferHttpStatus(httpStatus);
        ledger.setErrorCode(errorCode);
        ledger.setErrorMessage(errorMessage);
        ledger.setNextRetryAtUtc(nextRetryAtUtc);
        ledger.setGrantedAtUtc(now);

        ledgerRepository.saveAndFlush(ledger);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveEntitlement(UserEntitlementEntity entitlement) {
        entitlementRepository.saveAndFlush(entitlement);
    }
}
