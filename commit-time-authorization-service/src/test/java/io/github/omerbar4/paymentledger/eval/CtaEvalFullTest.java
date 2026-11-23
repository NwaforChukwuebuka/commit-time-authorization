package io.github.omerbar4.paymentledger.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.omerbar4.paymentledger.AbstractIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Seeds 11, 23, 37, 41, 59 at the seminar workload size. Excluded from the default test run.
 * {@code ./mvnw -Pcta-eval test} writes {@code results-java.json} next to {@code results.json}.
 */
@Tag("eval")
class CtaEvalFullTest extends AbstractIntegrationTest {

    @Autowired
    private CtaEvalRunner runner;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fiveSeedsMatchSeminarCtaOutcomes() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int seed : WorkloadGenerator.SEEDS) {
            runner.reset();
            Map<String, Object> row = runner.run(seed, WorkloadGenerator.N_LEGIT, WorkloadGenerator.N_REPEAT,
                    WorkloadGenerator.N_PER_SCENARIO);
            rows.add(row);
            assertSeminarCta(row);
        }
        Path out = resultsFile();
        objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), rows);
    }

    @SuppressWarnings("unchecked")
    private static void assertSeminarCta(Map<String, Object> row) {
        assertThat(row.get("model")).isEqualTo("CTA");
        assertThat(row.get("events")).isEqualTo(6000);
        assertThat(row.get("invalid_total")).isEqualTo(0);
        assertThat(row.get("invalid_value")).isEqualTo(0L);
        assertThat(row.get("legit_expected")).isEqualTo(3400);
        assertThat(row.get("legit_committed")).isEqualTo(3400);
        assertThat(row.get("false_rejections")).isEqualTo(0);
        assertThat(row.get("conservation_ok")).isEqualTo(true);
        assertThat((Map<String, Integer>) row.get("cta_reasons"))
                .containsEntry("OK", 3400)
                .containsEntry("P1_EVENT_ALREADY_COMMITTED", 1800)
                .containsEntry("P3_GRANT_CONSUMED", 200)
                .containsEntry("P3_GRANT_REVOKED", 200)
                .containsEntry("P4_PAYLOAD_MISMATCH", 200)
                .containsEntry("P5_ACCOUNT_NOT_ACTIVE", 200);
    }

    private static Path resultsFile() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.exists(cwd.resolve("results.json"))) {
            return cwd.resolve("results-java.json");
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.exists(parent.resolve("results.json"))) {
            return parent.resolve("results-java.json");
        }
        return cwd.resolve("results-java.json");
    }
}
