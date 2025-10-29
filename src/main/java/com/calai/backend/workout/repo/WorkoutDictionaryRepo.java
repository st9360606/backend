package com.calai.backend.workout.repo;

import com.calai.backend.workout.entity.WorkoutDictionary;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkoutDictionaryRepo extends JpaRepository<WorkoutDictionary, Long> {}