package com.caloshape.backend.referral.repo;

import com.caloshape.backend.referral.entity.UserReferralCodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserReferralCodeRepository extends JpaRepository<UserReferralCodeEntity, Long> {
    Optional<UserReferralCodeEntity> findByUserId(Long userId);
    Optional<UserReferralCodeEntity> findByPromoCodeAndActiveIsTrue(String promoCode);
}
