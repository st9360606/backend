package com.calai.backend.gemini;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WorkoutDictionarySeedSmokeTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void dataSql_should_seed_workout_dictionary_and_contain_other_exercise() {
        Integer cnt = jdbc.queryForObject("select count(*) from workout_dictionary", Integer.class);
        assertThat(cnt).isNotNull();
        assertThat(cnt).isGreaterThanOrEqualTo(4);

        Integer other = jdbc.queryForObject(
                "select count(*) from workout_dictionary where canonical_key = 'other_exercise'",
                Integer.class
        );
        assertThat(other).isEqualTo(1);
    }
}
