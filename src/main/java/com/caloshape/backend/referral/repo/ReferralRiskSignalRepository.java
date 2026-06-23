package com.caloshape.backend.referral.repo;

import com.caloshape.backend.referral.entity.ReferralRiskSignalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReferralRiskSignalRepository extends JpaRepository<ReferralRiskSignalEntity, Long> {
    List<ReferralRiskSignalEntity> findTop20ByClaimIdOrderByCreatedAtUtcDesc(Long claimId);
}
