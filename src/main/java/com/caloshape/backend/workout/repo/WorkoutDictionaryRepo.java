package com.caloshape.backend.workout.repo;

import com.caloshape.backend.workout.entity.WorkoutDictionary;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkoutDictionaryRepo extends JpaRepository<WorkoutDictionary, Long> {}
