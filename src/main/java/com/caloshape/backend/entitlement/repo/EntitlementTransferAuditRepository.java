package com.caloshape.backend.entitlement.repo;

import com.caloshape.backend.entitlement.entity.EntitlementTransferAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntitlementTransferAuditRepository extends JpaRepository<EntitlementTransferAuditEntity, Long> {
}
