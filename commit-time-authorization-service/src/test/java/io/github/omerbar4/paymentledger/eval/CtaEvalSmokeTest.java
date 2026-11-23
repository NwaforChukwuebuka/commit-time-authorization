package io.github.omerbar4.paymentledger.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** One small seed on PostgreSQL. Proves the scorer without the five-seed run. */
class CtaEvalSmokeTest extends io.github.omerbar4.paymentledger.AbstractIntegrationTest {

    @Autowired
    private CtaEvalRunner runner;

    @AfterEach
    void removeSeededBooks() {
        runner.reset();
    }

    @Test
    void scaledWorkloadHasNoInvalidPosting() {
        Map<String, Object> row = runner.run(11, 20, 2, 5);
        WorkloadGenerator.Expectation expected = WorkloadGenerator.expectation(20, 2, 5);

        assertThat(row.get("events")).isEqualTo(expected.events());
        assertThat(row.get("invalid_total")).isEqualTo(0);
        assertThat(row.get("invalid_value")).isEqualTo(0L);
        assertThat(row.get("false_rejections")).isEqualTo(0);
        assertThat(row.get("legit_expected")).isEqualTo(expected.legitExpected());
        assertThat(row.get("legit_committed")).isEqualTo(expected.legitExpected());
        assertThat(row.get("conservation_ok")).isEqualTo(true);
        assertThat(reasons(row))
                .containsEntry("OK", expected.ok())
                .containsEntry("P1_EVENT_ALREADY_COMMITTED", expected.p1())
                .containsEntry("P3_GRANT_CONSUMED", expected.p3Consumed())
                .containsEntry("P3_GRANT_REVOKED", expected.p3Revoked())
                .containsEntry("P4_PAYLOAD_MISMATCH", expected.p4())
                .containsEntry("P5_ACCOUNT_NOT_ACTIVE", expected.p5());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> reasons(Map<String, Object> row) {
        return (Map<String, Integer>) row.get("cta_reasons");
    }
}
