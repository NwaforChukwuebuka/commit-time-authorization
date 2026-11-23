package io.github.omerbar4.paymentledger.eval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Port of {@code build_workload} in {@code cta_experiment.py}. Account indexes are the prototype's
 * integer ids. The runner maps them to ledger account UUIDs. The Java {@link Random} stream is not
 * the Python one; CTA outcome counts do not depend on which accounts are paired.
 */
public final class WorkloadGenerator {

    public static final int N_ACCOUNTS = 200;
    public static final int N_LEGIT = 2000;
    public static final int N_REPEAT = 200;
    public static final int N_PER_SCENARIO = 200;
    public static final int BURST = 8;
    public static final int IDEM_TTL = 86_400;
    public static final int FRESHNESS = 300;
    public static final int[] SEEDS = {11, 23, 37, 41, 59};
    public static final BigDecimal OPENING_BALANCE = new BigDecimal("10000000.0000");

    private WorkloadGenerator() {
    }

    public static Workload build(int seed) {
        return build(seed, N_LEGIT, N_REPEAT, N_PER_SCENARIO);
    }

    public static Workload build(int seed, int nLegit, int nRepeat, int nPerScenario) {
        Random rng = new Random(seed);
        List<GrantSpec> grants = new ArrayList<>();
        List<Task> tasks = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < nLegit; i++) {
            GrantSpec grant = newGrant(rng, grants, ++n, null, null, null);
            tasks.add(new SeqTask(List.of(new EventStep(event(grant, "LEGIT", null, null, 1000, 5)))));
        }
        for (int i = 0; i < nRepeat; i++) {
            GrantSpec first = newGrant(rng, grants, ++n, null, null, null);
            GrantSpec second = newGrant(rng, grants, ++n, first.payer, first.payee, first.amount);
            tasks.add(new SeqTask(List.of(
                    new EventStep(event(first, "LEGIT", null, null, 1000, 5)),
                    new EventStep(event(second, "LEGIT_REPEAT", null, null, 1000, 5)))));
        }
        for (int i = 0; i < nPerScenario; i++) {
            GrantSpec s1 = newGrant(rng, grants, ++n, null, null, null);
            tasks.add(new SeqTask(List.of(
                    new EventStep(event(s1, "LEGIT", null, null, 1000, 5)),
                    new EventStep(event(s1, "S1_REDELIVERY", null, null, 1060, 65)))));
            GrantSpec s2 = newGrant(rng, grants, ++n, null, null, null);
            List<EventSpec> burst = new ArrayList<>();
            burst.add(event(s2, "LEGIT", null, null, 1000, 5));
            for (int copy = 0; copy < BURST - 1; copy++) {
                burst.add(event(s2, "S2_BURST", null, null, 1000, 5));
            }
            tasks.add(new BurstTask(burst));
            GrantSpec s3 = newGrant(rng, grants, ++n, null, null, null);
            tasks.add(new SeqTask(List.of(
                    new EventStep(event(s3, "LEGIT", null, null, 1000, 5)),
                    new EventStep(event(s3, "S3_REKEYED", "EVT-" + s3.authRef + "-R", null, 1100, 5)))));
            GrantSpec s4a = newGrant(rng, grants, ++n, null, null, null);
            tasks.add(new SeqTask(List.of(new StaleStep(event(s4a, "S4_STALE_FROZEN", null, null, 1000, 5), "freeze"))));
            GrantSpec s4b = newGrant(rng, grants, ++n, null, null, null);
            tasks.add(new SeqTask(List.of(new StaleStep(event(s4b, "S4_STALE_REVOKED", null, null, 1000, 5), "revoke"))));
            GrantSpec s5 = newGrant(rng, grants, ++n, null, null, null);
            tasks.add(new SeqTask(List.of(
                    new EventStep(event(s5, "S5_TAMPERED", "EVT-" + s5.authRef + "-T", s5.amount * 3, 1000, 5)),
                    new EventStep(event(s5, "LEGIT", null, null, 1010, 5)))));
            GrantSpec s6 = newGrant(rng, grants, ++n, null, null, null);
            tasks.add(new SeqTask(List.of(
                    new EventStep(event(s6, "LEGIT", null, null, 1000, 5)),
                    new EventStep(event(s6, "S6_AGED", null, null, 1000 + IDEM_TTL + 3600, IDEM_TTL + 3605)))));
        }
        Collections.shuffle(tasks, rng);
        int extra = assignFreezePayers(grants, tasks);
        return new Workload(grants, tasks, N_ACCOUNTS + extra);
    }

    /** Outcome counts implied by the scenario sizes. They match the CTA rows of {@code results.json}. */
    public static Expectation expectation(int nLegit, int nRepeat, int nPerScenario) {
        int legitExpected = nLegit + (2 * nRepeat) + (5 * nPerScenario);
        int p1 = nPerScenario * (1 + (BURST - 1) + 1);
        int events = nLegit + (2 * nRepeat) + (nPerScenario * (2 + BURST + 2 + 1 + 1 + 2 + 2));
        return new Expectation(events, legitExpected, legitExpected, p1, nPerScenario, nPerScenario, nPerScenario,
                nPerScenario);
    }

    public static int eventCount(Workload workload) {
        int count = 0;
        for (Task task : workload.tasks()) {
            if (task instanceof BurstTask burst) {
                count += burst.events().size();
            } else if (task instanceof SeqTask seq) {
                count += seq.steps().size();
            }
        }
        return count;
    }

    public static int staleGrantCount(Workload workload) {
        int count = 0;
        for (Task task : workload.tasks()) {
            if (task instanceof SeqTask seq) {
                for (Step step : seq.steps()) {
                    if (step instanceof StaleStep) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static int assignFreezePayers(List<GrantSpec> grants, List<Task> tasks) {
        Map<String, GrantSpec> byRef = new HashMap<>();
        for (GrantSpec grant : grants) {
            byRef.put(grant.authRef, grant);
        }
        int extra = 0;
        for (Task task : tasks) {
            if (task instanceof SeqTask seq && !seq.steps().isEmpty()
                    && seq.steps().getFirst() instanceof StaleStep stale && "freeze".equals(stale.mutate())) {
                int payer = N_ACCOUNTS + extra;
                extra++;
                stale.event().payer = payer;
                byRef.get(stale.event().authRef).payer = payer;
            }
        }
        return extra;
    }

    private static GrantSpec newGrant(Random rng, List<GrantSpec> grants, int n, Integer payer, Integer payee,
                                      Integer amount) {
        int payerId = payer != null ? payer : rng.nextInt(N_ACCOUNTS);
        int payeeId = payee != null ? payee : otherAccount(rng, payerId);
        int value = amount != null ? amount : 500 + rng.nextInt(50_000 - 500);
        GrantSpec grant = new GrantSpec("AUTH-" + String.format("%06d", n), payerId, payeeId, value);
        grants.add(grant);
        return grant;
    }

    private static int otherAccount(Random rng, int payer) {
        int payee = rng.nextInt(N_ACCOUNTS - 1);
        return payee >= payer ? payee + 1 : payee;
    }

    private static EventSpec event(GrantSpec grant, String kind, String eventId, Integer amount, long now, long age) {
        return new EventSpec(eventId != null ? eventId : "EVT-" + grant.authRef, grant.authRef, grant.payer,
                grant.payee, amount != null ? amount : grant.amount, kind, now, now - age);
    }

    public record Expectation(int events, int legitExpected, int ok, int p1, int p3Consumed, int p3Revoked, int p4,
                              int p5) {
    }

    public record Workload(List<GrantSpec> grants, List<Task> tasks, int accountCount) {
    }

    public static final class GrantSpec {
        public final String authRef;
        public int payer;
        public final int payee;
        public final int amount;

        GrantSpec(String authRef, int payer, int payee, int amount) {
            this.authRef = authRef;
            this.payer = payer;
            this.payee = payee;
            this.amount = amount;
        }
    }

    public static final class EventSpec {
        public final String eventId;
        public final String authRef;
        public int payer;
        public final int payee;
        public final int amount;
        public final String kind;
        public final long now;
        public final long issuedAt;

        EventSpec(String eventId, String authRef, int payer, int payee, int amount, String kind, long now,
                  long issuedAt) {
            this.eventId = eventId;
            this.authRef = authRef;
            this.payer = payer;
            this.payee = payee;
            this.amount = amount;
            this.kind = kind;
            this.now = now;
            this.issuedAt = issuedAt;
        }
    }

    public sealed interface Task permits SeqTask, BurstTask {
    }

    public record SeqTask(List<Step> steps) implements Task {
    }

    public record BurstTask(List<EventSpec> events) implements Task {
    }

    public sealed interface Step permits EventStep, StaleStep {
    }

    public record EventStep(EventSpec event) implements Step {
    }

    public record StaleStep(EventSpec event, String mutate) implements Step {
    }
}
