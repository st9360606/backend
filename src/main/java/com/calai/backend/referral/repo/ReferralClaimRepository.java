package com.calai.backend.referral.repo;

import com.calai.backend.referral.entity.ReferralClaimEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
    List<ReferralClaimEntity> findPendingToProcess(@Param("now") Instant now);

    /**
     * Commercial critical lock:
     * Only one worker can move a claim from PENDING_VERIFICATION to PROCESSING_REWARD.
     *
     * If two backend nodes try to process the same claim, only one update returns 1.
     * The other returns 0 and must skip.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update ReferralClaimEntity c
           set c.status = 'PROCESSING_REWARD',
               c.updatedAtUtc = :now
         where c.id = :claimId
           and c.status = 'PENDING_VERIFICATION'
           and c.verificationDeadlineUtc <= :now
    """)
    int claimForRewardProcessing(
            @Param("claimId") Long claimId,
            @Param("now") Instant now
    );

    /**
     * Optional safety net:
     * If the app crashes while a claim is PROCESSING_REWARD, this query can be used by a repair job.
     */
    @Query("""
        select c from ReferralClaimEntity c
        where c.status = 'PROCESSING_REWARD'
          and c.updatedAtUtc <= :cutoff
        order by c.updatedAtUtc asc
    """)
    List<ReferralClaimEntity> findStaleProcessingClaims(@Param("cutoff") Instant cutoff);
}
