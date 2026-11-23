package io.github.omerbar4.paymentledger.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkloadGeneratorTest {

    @Test
    void fullWorkloadMatchesTheSeminarShape() {
        WorkloadGenerator.Workload workload = WorkloadGenerator.build(11);
        WorkloadGenerator.Expectation expected = WorkloadGenerator.expectation(
                WorkloadGenerator.N_LEGIT, WorkloadGenerator.N_REPEAT, WorkloadGenerator.N_PER_SCENARIO);

        assertThat(workload.grants()).hasSize(3800);
        assertThat(workload.accountCount()).isEqualTo(400);
        assertThat(WorkloadGenerator.eventCount(workload)).isEqualTo(6000);
        assertThat(WorkloadGenerator.staleGrantCount(workload)).isEqualTo(400);
        assertThat(expected.events()).isEqualTo(6000);
        assertThat(expected.legitExpected()).isEqualTo(3400);
        assertThat(expected.ok()).isEqualTo(3400);
        assertThat(expected.p1()).isEqualTo(1800);
        assertThat(expected.p3Consumed()).isEqualTo(200);
        assertThat(expected.p3Revoked()).isEqualTo(200);
        assertThat(expected.p4()).isEqualTo(200);
        assertThat(expected.p5()).isEqualTo(200);
    }
}
