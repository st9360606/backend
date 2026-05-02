package com.calai.backend.referral.repo;

import com.calai.backend.referral.entity.MembershipRewardLedgerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MembershipRewardLedgerRepository extends JpaRepository<MembershipRewardLedgerEntity, Long> {

    List<MembershipRewardLedgerEntity> findTop50ByUserIdOrderByGrantedAtUtcDesc(Long userId);

    Optional<MembershipRewardLedgerEntity> findTop1ByUserIdOrderByGrantedAtUtcDesc(Long userId);

    boolean existsBySourceTypeAndSourceRefIdAndGrantStatus(
            String sourceType,
            Long sourceRefId,
            String grantStatus
    );

    Optional<MembershipRewardLedgerEntity> findTopBySourceTypeAndSourceRefIdOrderByGrantedAtUtcDesc(
            String sourceType,
            Long sourceRefId
    );

    Optional<MembershipRewardLedgerEntity> findTopBySourceTypeAndSourceRefIdAndGrantStatusOrderByGrantedAtUtcDesc(
            String sourceType,
            Long sourceRefId,
            String grantStatus
    );

    Optional<MembershipRewardLedgerEntity> findTopBySourceTypeAndSourceRefIdOrderByAttemptNoDesc(
            String sourceType,
            Long sourceRefId
    );

    Optional<MembershipRewardLedgerEntity> findTopBySourceTypeAndSourceRefIdAndRewardChannelOrderByGrantedAtUtcDesc(
            String sourceType,
            Long sourceRefId,
            String rewardChannel
    );

    Optional<MembershipRewardLedgerEntity> findTopBySourceTypeAndSourceRefIdAndRewardChannelOrderByGrantedAtUtcAsc(
            String sourceType,
            Long sourceRefId,
            String rewardChannel
    );

    long countBySourceTypeAndSourceRefIdAndRewardChannel(
            String sourceType,
            Long sourceRefId,
            String rewardChannel
    );

    List<MembershipRewardLedgerEntity> findBySourceTypeAndSourceRefIdOrderByAttemptNoAsc(
            String sourceType,
            Long sourceRefId
    );

    List<MembershipRewardLedgerEntity> findBySourceTypeAndSourceRefId(
            String sourceType,
            Long sourceRefId
    );

    Optional<MembershipRewardLedgerEntity> findTopBySourceTypeAndSourceRefIdAndRewardChannelOrderByAttemptNoDesc(
            String sourceType,
            Long sourceRefId,
            String rewardChannel
    );

    Optional<MembershipRewardLedgerEntity> findTopBySourceTypeAndSourceRefIdAndRewardChannelAndGrantStatusOrderByGrantedAtUtcDesc(
            String sourceType,
            Long sourceRefId,
            String rewardChannel,
            String grantStatus
    );

    long countBySourceTypeAndSourceRefIdAndRewardChannelAndGrantStatusIn(
            String sourceType,
            Long sourceRefId,
            String rewardChannel,
            java.util.Collection<String> grantStatuses
    );
}
