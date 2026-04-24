package com.calai.backend.referral.repo;

import com.calai.backend.referral.entity.MembershipRewardLedgerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MembershipRewardLedgerRepository extends JpaRepository<MembershipRewardLedgerEntity, Long> {
    List<MembershipRewardLedgerEntity> findTop50ByUserIdOrderByGrantedAtUtcDesc(Long userId);
    Optional<MembershipRewardLedgerEntity> findTop1ByUserIdOrderByGrantedAtUtcDesc(Long userId);
    boolean existsBySourceTypeAndSourceRefId(String sourceType, Long sourceRefId);
}
