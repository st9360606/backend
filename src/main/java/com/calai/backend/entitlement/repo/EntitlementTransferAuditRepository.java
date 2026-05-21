package com.calai.backend.entitlement.repo;

import com.calai.backend.entitlement.entity.EntitlementTransferAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntitlementTransferAuditRepository extends JpaRepository<EntitlementTransferAuditEntity, Long> {
}
