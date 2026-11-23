package io.github.omerbar4.paymentledger.metrics;

import io.github.omerbar4.paymentledger.domain.CommitOutcome;
import io.github.omerbar4.paymentledger.domain.CommitReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Commit-time authorization counters. Tags are limited to the process role, the commit outcome,
 * and {@code decision.reason}. Event ids, grant refs, account ids, and amounts are never tags.
 *
 * <p>{@code cta.commits{role, outcome, reason}} counts durable decisions. {@code role} is
 * {@code intra}, {@code payer}, or {@code payee}. {@code outcome} is the lower-case
 * {@link CommitOutcome}. {@code reason} is the {@link CommitReason} name, including {@code OK}.
 */
@Component
public class CtaCommitMetrics {

    static final String COMMITS = "cta.commits";

    private static final String[] ROLES = {"intra", "payer", "payee"};

    private final MeterRegistry registry;

    public CtaCommitMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String role : ROLES) {
            for (CommitOutcome outcome : CommitOutcome.values()) {
                for (CommitReason reason : CommitReason.values()) {
                    counter(role, outcome, reason);
                }
            }
        }
    }

    /**
     * Count one durable decision. {@code role} is the raw {@code cta.role} value ({@code INTRA},
     * {@code PAYER}, {@code PAYEE}); it is normalized to a lower-case tag.
     */
    public void recordCommit(String role, CommitOutcome outcome, CommitReason reason) {
        counter(roleTag(role), outcome, reason).increment();
    }

    public static String roleTag(String role) {
        if (role == null || role.isBlank()) {
            return "intra";
        }
        return role.toLowerCase(Locale.ROOT);
    }

    private Counter counter(String role, CommitOutcome outcome, CommitReason reason) {
        return Counter.builder(COMMITS)
                .description("Durable commit-time decisions by role, outcome, and reason")
                .tag("role", role)
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .tag("reason", reason.name())
                .register(registry);
    }
}
