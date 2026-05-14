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
        select count(c) from ReferralClaimEntity c
        where c.inviterUserId = :inviterUserId
          and c.status in :statuses
    """)
    long countByInviterUserIdAndStatusIn(
            @Param("inviterUserId") Long inviterUserId,
            @Param("statuses") List<String> statuses
    );

    /**
     * New commercial status is PENDING_COOLDOWN. PENDING_VERIFICATION is kept as
     * a legacy alias so existing rows still get processed after the deployment.
     */
    @Query("""
        select c from ReferralClaimEntity c
        where c.status in ('PENDING_COOLDOWN', 'PENDING_VERIFICATION')
          and coalesce(c.cooldownUntilUtc, c.verificationDeadlineUtc) <= :now
        order by coalesce(c.cooldownUntilUtc, c.verificationDeadlineUtc) asc
    """)
    List<ReferralClaimEntity> findPendingToProcess(@Param("now") Instant now);

    /**
     * Commercial critical lock:
     * Only one worker can move a claim from PENDING_COOLDOWN/PENDING_VERIFICATION
     * to PROCESSING_REWARD.
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
           and c.status in ('PENDING_COOLDOWN', 'PENDING_VERIFICATION')
           and coalesce(c.cooldownUntilUtc, c.verificationDeadlineUtc) <= :now
    """)
    int claimForRewardProcessing(
            @Param("claimId") Long claimId,
            @Param("now") Instant now
    );

    /**
     * Pending subscription claims must not live forever. If the invitee does not
     * complete a valid paid subscription before the attribution window expires,
     * the claim is closed as EXPIRED.
     */
    @Query("""
        select c from ReferralClaimEntity c
        where c.status = 'PENDING_SUBSCRIPTION'
          and c.createdAtUtc <= :createdBeforeOrAt
        order by c.createdAtUtc asc
    """)
    List<ReferralClaimEntity> findPendingSubscriptionExpired(
            @Param("createdBeforeOrAt") Instant createdBeforeOrAt
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
