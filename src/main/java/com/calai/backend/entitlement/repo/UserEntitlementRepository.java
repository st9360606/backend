package com.calai.backend.entitlement.repo;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserEntitlementRepository extends JpaRepository<UserEntitlementEntity, String> {

    Optional<UserEntitlementEntity> findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc(String purchaseTokenHash);

    Optional<UserEntitlementEntity> findTopByLinkedPurchaseTokenHashOrderByUpdatedAtUtcDesc(
            String linkedPurchaseTokenHash
    );


    @Query("""
        select e from UserEntitlementEntity e
        where e.userId = :userId
          and e.status = 'ACTIVE'
          and e.validFromUtc <= :now
          and e.validToUtc > :now
          and (e.paymentState is null or e.paymentState not in ('PENDING','ON_HOLD', 'EXPIRED', 'REVOKED', 'PENDING_PURCHASE_CANCELED','UNKNOWN','PAUSED'))
          and not (e.source = 'INTERNAL' and e.entitlementType = 'TRIAL')
        order by e.validToUtc desc
    """)
    List<UserEntitlementEntity> findActive(
            @Param("userId") Long userId,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Query("""
        select e from UserEntitlementEntity e
        where e.userId = :userId
          and e.status = 'ACTIVE'
          and e.validFromUtc <= :now
          and e.validToUtc > :now
          and (e.paymentState is null or e.paymentState not in ('PENDING','ON_HOLD', 'EXPIRED', 'REVOKED', 'PENDING_PURCHASE_CANCELED','UNKNOWN','PAUSED'))
          and not (e.source = 'INTERNAL' and e.entitlementType = 'TRIAL')
        order by
          case e.entitlementType
            when 'YEARLY' then 3
            when 'MONTHLY' then 2
            when 'TRIAL' then 1
            else 0
          end desc,
          e.validToUtc desc
    """)
    List<UserEntitlementEntity> findActiveBestFirst(
            @Param("userId") Long userId,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
    update UserEntitlementEntity e
       set e.status = :status,
           e.updatedAtUtc = :now,
           e.revokedAtUtc = :revokedAtUtc,
           e.lastRtdnAtUtc = :lastRtdnAtUtc,
           e.subscriptionState = coalesce(:subscriptionState, e.subscriptionState),
           e.paymentState = coalesce(:paymentState, e.paymentState),
           e.closeReason = coalesce(:closeReason, e.closeReason),
           e.lastGoogleVerifiedAtUtc = coalesce(:lastGoogleVerifiedAtUtc, e.lastGoogleVerifiedAtUtc),
           e.validToUtc = case when e.validToUtc > :now then :now else e.validToUtc end
     where e.purchaseTokenHash = :purchaseTokenHash
       and e.status = 'ACTIVE'
""")
    int closeActiveByPurchaseTokenHash(
            @Param("purchaseTokenHash") String purchaseTokenHash,
            @Param("status") String status,
            @Param("now") Instant now,
            @Param("revokedAtUtc") Instant revokedAtUtc,
            @Param("lastRtdnAtUtc") Instant lastRtdnAtUtc,
            @Param("subscriptionState") String subscriptionState,
            @Param("paymentState") String paymentState,
            @Param("closeReason") String closeReason,
            @Param("lastGoogleVerifiedAtUtc") Instant lastGoogleVerifiedAtUtc
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
    update UserEntitlementEntity e
       set e.status = 'EXPIRED',
           e.paymentState = 'EXPIRED',
           e.closeReason = 'EXPIRED_BY_TIME',
           e.updatedAtUtc = :now
     where e.status = 'ACTIVE'
       and e.validToUtc <= :now
""")
    int expireAllEndedEntitlements(@Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
    update UserEntitlementEntity e
       set e.status = 'EXPIRED',
           e.paymentState = 'EXPIRED',
           e.closeReason = 'SUPERSEDED_BY_NEW_ENTITLEMENT',
           e.updatedAtUtc = :now,
           e.validToUtc = case when e.validToUtc > :now then :now else e.validToUtc end
     where e.userId = :userId
       and e.status = 'ACTIVE'
       and e.id <> :keepId
""")
    int expireActiveByUserIdExcept(
            @Param("userId") Long userId,
            @Param("keepId") String keepId,
            @Param("now") Instant now
    );

    @Query("""
    select e from UserEntitlementEntity e
    where e.source = 'GOOGLE_PLAY'
      and e.status = 'ACTIVE'
      and e.purchaseTokenCiphertext is not null
      and (e.lastGoogleVerifiedAtUtc is null or e.lastGoogleVerifiedAtUtc <= :verifiedBefore)
    order by case when e.lastGoogleVerifiedAtUtc is null then 0 else 1 end asc,
             e.lastGoogleVerifiedAtUtc asc,
             e.updatedAtUtc asc
""")
    List<UserEntitlementEntity> findActiveGooglePlayDueForReverify(
            @Param("verifiedBefore") Instant verifiedBefore,
            Pageable pageable
    );

    @Query("""
    select e from UserEntitlementEntity e
    where e.source = 'GOOGLE_PLAY'
      and e.status = 'ACTIVE'
      and e.purchaseTokenCiphertext is not null
      and (
          e.acknowledgementState is null
          or e.acknowledgementState <> 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED'
      )
      and e.updatedAtUtc <= :updatedBefore
    order by e.updatedAtUtc asc
""")
    List<UserEntitlementEntity> findAckPendingGooglePlayEntitlements(
            @Param("updatedBefore") Instant updatedBefore,
            Pageable pageable
    );

}
