package com.calai.backend.gemini;

import com.calai.backend.foodlog.task.HealthScore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

public class HealthScoreTest {

    private final ObjectMapper om = new ObjectMapper();
    private final HealthScore v1 = new HealthScore();

    @Test
    void score_should_be_in_range() throws Exception {
        ObjectNode n = (ObjectNode) om.readTree("""
          {"fiber":6,"sugar":5,"sodium":450,"protein":12,"fat":8}
        """);

        Integer s = v1.score(n);
        assertThat(s).isNotNull();
        assertThat(s).isBetween(1, 10);
    }

    @Test
    void high_sugar_and_sodium_should_penalize() throws Exception {
        ObjectNode n = (ObjectNode) om.readTree("""
          {"fiber":1,"sugar":25,"sodium":900,"protein":3,"fat":5}
        """);

        Integer s = v1.score(n);
        assertThat(s).isNotNull();
        assertThat(s).isBetween(1, 10);
        assertThat(s).isLessThanOrEqualTo(5);
    }

    @Test
    void too_few_inputs_should_return_null() throws Exception {
        ObjectNode n = (ObjectNode) om.readTree("""
          {"fiber":6}
        """);

        Integer s = v1.score(n);
        assertThat(s).isNull();
    }
}
