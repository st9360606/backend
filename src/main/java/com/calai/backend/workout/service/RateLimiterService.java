package com.calai.backend.workout.service;

import com.calai.backend.workout.repo.WorkoutAliasEventRepo;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class RateLimiterService {
    private final WorkoutAliasEventRepo eventRepo;
    public RateLimiterService(WorkoutAliasEventRepo repo){ this.eventRepo = repo; }

    /** 同戶 24h 內「不同片語」上限（先放寬 100；你可改 10/20） */
    public boolean allowNewPhrase(Long userId) {
        Instant since = Instant.now().minus(1, ChronoUnit.DAYS);
        long distinctPhrases = eventRepo.countDistinctPhrasesSince(userId, since);
        return distinctPhrases < 100;
    }
}
