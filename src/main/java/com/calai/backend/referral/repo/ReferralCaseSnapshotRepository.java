package com.calai.backend.referral.repo;

import com.calai.backend.referral.entity.ReferralCaseSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReferralCaseSnapshotRepository extends JpaRepository<ReferralCaseSnapshotEntity, Long> {
    Optional<ReferralCaseSnapshotEntity> findByInviterUserId(Long inviterUserId);
}
