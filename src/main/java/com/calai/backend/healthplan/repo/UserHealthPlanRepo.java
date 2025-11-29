package com.calai.backend.healthplan.repo;

import com.calai.backend.healthplan.entity.UserHealthPlan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserHealthPlanRepo extends JpaRepository<UserHealthPlan, Long> {
}
