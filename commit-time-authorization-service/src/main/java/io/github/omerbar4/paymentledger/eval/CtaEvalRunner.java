package io.github.omerbar4.paymentledger.eval;

import io.github.omerbar4.paymentledger.domain.Account;
import io.github.omerbar4.paymentledger.domain.AuthGrant;
import io.github.omerbar4.paymentledger.eval.WorkloadGenerator.BurstTask;
import io.github.omerbar4.paymentledger.eval.WorkloadGenerator.EventSpec;
import io.github.omerbar4.paymentledger.eval.WorkloadGenerator.EventStep;
import io.github.omerbar4.paymentledger.eval.WorkloadGenerator.GrantSpec;
import io.github.omerbar4.paymentledger.eval.WorkloadGenerator.SeqTask;
import io.github.omerbar4.paymentledger.eval.WorkloadGenerator.StaleStep;
import io.github.omerbar4.paymentledger.eval.WorkloadGenerator.Step;
import io.github.omerbar4.paymentledger.eval.WorkloadGenerator.Task;
import io.github.omerbar4.paymentledger.eval.WorkloadGenerator.Workload;
import io.github.omerbar4.paymentledger.repository.AccountRepository;
import io.github.omerbar4.paymentledger.repository.AuthGrantRepository;
import io.github.omerbar4.paymentledger.service.AccountService;
import io.github.omerbar4.paymentledger.service.CommitAuthorizationService;
import io.github.omerbar4.paymentledger.service.GrantService;
import io.github.omerbar4.paymentledger.service.PaymentEventCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one CTA workload against {@link CommitAuthorizationService} and scores it the way
 * {@code run()} does in {@code cta_experiment.py}. Burst copies are applied in order; the 16-thread
 * race is covered by {@code CommitIntraBankIntegrationTest}.
 */
@Service
public class CtaEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(CtaEvalRunner.class);
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final int CHUNK = 500;

    private final AccountRepository accounts;
    private final AuthGrantRepository grants;
    private final AccountService accountService;
    private final GrantService grantService;
    private final CommitAuthorizationService commit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CtaEvalRunner(AccountRepository accounts, AuthGrantRepository grants, AccountService accountService,
                         GrantService grantService, CommitAuthorizationService commit, JdbcTemplate jdbc,
                         PlatformTransactionManager transactions) {
        this.accounts = accounts;
        this.grants = grants;
        this.accountService = accountService;
        this.grantService = grantService;
        this.commit = commit;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Drops ledger data so the next seed can reuse AUTH-000001 style ids. */
    public void reset() {
        jdbc.execute("TRUNCATE TABLE accounts, decision RESTART IDENTITY CASCADE");
    }

    public Map<String, Object> run(int seed, int nLegit, int nRepeat, int nPerScenario) {
        Workload workload = WorkloadGenerator.build(seed, nLegit, nRepeat, nPerScenario);
        log.info("CTA eval seed {} starting", seed);
        List<UUID> accountIds = createBooks(workload.accountCount());
        openGrants(workload, accountIds);
        BigDecimal opening = sumBalances(accountIds);

        Map<String, Integer> grantAmounts = new HashMap<>();
        for (GrantSpec grant : workload.grants()) {
            grantAmounts.put(grant.authRef, grant.amount);
        }
        Map<String, String> staleRefs = new HashMap<>();
        List<Double> latencies = new ArrayList<>();
        List<String> eventIds = new ArrayList<>();

        long started = System.nanoTime();
        for (Task task : workload.tasks()) {
            if (task instanceof BurstTask burst) {
                for (EventSpec event : burst.events()) {
                    process(event, null, accountIds, latencies, eventIds);
                }
            } else if (task instanceof SeqTask seq) {
                for (Step step : seq.steps()) {
                    if (step instanceof StaleStep stale) {
                        staleRefs.put(stale.event().authRef, stale.event().kind);
                        process(stale.event(), stale.mutate(), accountIds, latencies, eventIds);
                    } else if (step instanceof EventStep eventStep) {
                        process(eventStep.event(), null, accountIds, latencies, eventIds);
                    }
                }
            }
        }
        double wallSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
        Map<String, Object> row = score(seed, eventIds, latencies, wallSeconds, grantAmounts, staleRefs, accountIds, opening);
        log.info("CTA eval seed {} finished in {}s, invalid {}, false rejections {}", seed, row.get("wall_s"),
                row.get("invalid_total"), row.get("false_rejections"));
        return row;
    }

    private void process(EventSpec event, String mutate, List<UUID> accountIds, List<Double> latencies,
                         List<String> eventIds) {
        if ("freeze".equals(mutate)) {
            accountService.freeze(accountIds.get(event.payer));
        } else if ("revoke".equals(mutate)) {
            grantService.revoke(event.authRef);
        }
        PaymentEventCommand command = new PaymentEventCommand(event.eventId, event.authRef,
                accountIds.get(event.payer), accountIds.get(event.payee), BigDecimal.valueOf(event.amount), "NGN",
                EPOCH.plusSeconds(event.issuedAt), EPOCH.plusSeconds(event.now), event.kind);
        long t0 = System.nanoTime();
        commit.commitIntraBank(command);
        latencies.add((System.nanoTime() - t0) / 1_000_000.0);
        eventIds.add(event.eventId);
    }

    private List<UUID> createBooks(int count) {
        return tx.execute(status -> {
            List<UUID> ids = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Account account = new Account("cta-" + i, "NGN", false);
                account.seedBalance(WorkloadGenerator.OPENING_BALANCE);
                ids.add(accounts.save(account).getId());
            }
            return ids;
        });
    }

    private void openGrants(Workload workload, List<UUID> accountIds) {
        tx.executeWithoutResult(status -> {
            for (GrantSpec grant : workload.grants()) {
                grants.save(AuthGrant.open(grant.authRef, accountIds.get(grant.payer), accountIds.get(grant.payee),
                        BigDecimal.valueOf(grant.amount), "NGN"));
            }
        });
    }

    private Map<String, Object> score(int seed, List<String> eventIds, List<Double> latencies, double wallSeconds,
                                      Map<String, Integer> grantAmounts, Map<String, String> staleRefs,
                                      List<UUID> accountIds, BigDecimal opening) {
        Map<String, List<Post>> byRef = new HashMap<>();
        for (Post post : postsFor(grantAmounts.keySet())) {
            byRef.computeIfAbsent(post.authRef, key -> new ArrayList<>()).add(post);
        }
        Map<String, Integer> invalid = new LinkedHashMap<>();
        long invalidValue = 0;
        int legitOk = 0;
        for (Map.Entry<String, List<Post>> entry : byRef.entrySet()) {
            List<Post> posts = entry.getValue();
            if (staleRefs.containsKey(entry.getKey())) {
                invalid.merge(staleRefs.get(entry.getKey()), posts.size(), Integer::sum);
                for (Post post : posts) {
                    invalidValue += post.amount.longValue();
                }
                continue;
            }
            BigDecimal expected = BigDecimal.valueOf(grantAmounts.get(entry.getKey()));
            List<Post> good = posts.stream().filter(post -> post.amount.compareTo(expected) == 0).toList();
            List<Post> surplus = new ArrayList<>(posts);
            if (!good.isEmpty()) {
                surplus.remove(good.getFirst());
                legitOk++;
            }
            for (Post post : surplus) {
                String label = "LEGIT".equals(post.kind) || "LEGIT_REPEAT".equals(post.kind) ? "S2_BURST" : post.kind;
                invalid.merge(label, 1, Integer::sum);
                invalidValue += post.amount.longValue();
            }
        }
        int legitExpected = grantAmounts.size() - staleRefs.size();
        List<Double> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        double wall = round(wallSeconds, 2);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("model", "CTA");
        row.put("seed", seed);
        row.put("events", eventIds.size());
        row.put("wall_s", wall);
        row.put("lat_p50_ms", round(median(sorted), 3));
        row.put("lat_p95_ms", round(sorted.get((int) (sorted.size() * 0.95)), 3));
        row.put("throughput_eps", round(eventIds.size() / wallSeconds, 1));
        row.put("invalid_postings", invalid);
        row.put("invalid_total", invalid.values().stream().mapToInt(Integer::intValue).sum());
        row.put("invalid_value", invalidValue);
        row.put("legit_expected", legitExpected);
        row.put("legit_committed", legitOk);
        row.put("false_rejections", legitExpected - legitOk);
        row.put("conservation_ok", sumBalances(accountIds).compareTo(opening) == 0);
        row.put("cta_reasons", reasonCounts(new ArrayList<>(new LinkedHashSet<>(eventIds))));
        return row;
    }

    private List<Post> postsFor(java.util.Set<String> authRefs) {
        List<Post> posts = new ArrayList<>();
        List<String> refs = List.copyOf(authRefs);
        for (int offset = 0; offset < refs.size(); offset += CHUNK) {
            List<String> chunk = refs.subList(offset, Math.min(offset + CHUNK, refs.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            posts.addAll(jdbc.query(
                    "select auth_ref, kind, amount from ledger where auth_ref in (" + placeholders + ")",
                    (rs, row) -> new Post(rs.getString("auth_ref"), rs.getString("kind"), rs.getBigDecimal("amount")),
                    chunk.toArray()));
        }
        return posts;
    }

    private Map<String, Integer> reasonCounts(List<String> eventIds) {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (int offset = 0; offset < eventIds.size(); offset += CHUNK) {
            List<String> chunk = eventIds.subList(offset, Math.min(offset + CHUNK, eventIds.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            jdbc.query("select reason, count(*) as n from decision where event_id in (" + placeholders + ") group by reason",
                    rs -> {
                        reasons.merge(rs.getString("reason"), rs.getInt("n"), Integer::sum);
                    }, chunk.toArray());
        }
        return reasons;
    }

    private BigDecimal sumBalances(List<UUID> accountIds) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int offset = 0; offset < accountIds.size(); offset += CHUNK) {
            List<UUID> chunk = accountIds.subList(offset, Math.min(offset + CHUNK, accountIds.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            BigDecimal part = jdbc.queryForObject(
                    "select coalesce(sum(balance), 0) from accounts where id in (" + placeholders + ")",
                    BigDecimal.class, chunk.toArray());
            sum = sum.add(part);
        }
        return sum;
    }

    private static double median(List<Double> sorted) {
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    private record Post(String authRef, String kind, BigDecimal amount) {
    }
}
