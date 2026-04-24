package com.calai.backend.referral.repo;

import com.calai.backend.referral.entity.ReferralClaimEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReferralClaimRepository extends JpaRepository<ReferralClaimEntity, Long> {
    Optional<ReferralClaimEntity> findByInviteeUserId(Long inviteeUserId);
    Optional<ReferralClaimEntity> findByPurchaseTokenHash(String purchaseTokenHash);
    List<ReferralClaimEntity> findTop20ByInviterUserIdOrderByCreatedAtUtcDesc(Long inviterUserId);
    long countByInviterUserIdAndStatus(Long inviterUserId, String status);

    @Query("""
        select c from ReferralClaimEntity c
        where c.status = 'PENDING_VERIFICATION'
          and c.verificationDeadlineUtc <= :now
        order by c.verificationDeadlineUtc asc
    """)
    List<ReferralClaimEntity> findPendingToProcess(Instant now);
}
