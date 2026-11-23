# Runbook: Idempotent Payment Ledger API

How to run, observe, load-test and restart-test the service **locally**, and how to demo it.

> **Scope.** Everything here runs on one machine with Docker. It validates correctness under concurrency and restarts. It is **not** a deployed production service, and the load-test numbers are **not** a capacity benchmark: they depend on the laptop and use one API instance and one PostgreSQL container.

## 1. Architecture in one minute

```
client ──HTTP──► Spring Boot API (Java 21)                       PostgreSQL 16 (Flyway schema)
                 controller → service (@Transactional) → repo ──► accounts / transactions / ledger_entries
                 /actuator/health, /actuator/prometheus            UNIQUE(idempotency_key), CHECKs,
                 Micrometer: HTTP timers + ledger counters         deferred "debits = credits" trigger,
                                                                   append-only ledger trigger
```

A `POST /transactions` runs in one database transaction with these steps:
1. Look the key up; if it exists, return a replay.
2. Lock both accounts (`SELECT … FOR UPDATE`, in id order).
3. Claim the key with `INSERT … ON CONFLICT (idempotency_key) DO NOTHING`.
4. Write one DEBIT and one CREDIT and update the balances.
5. Commit.

A concurrent request with the same key waits on the unique index and then returns the winner's transaction. Because the key lives in PostgreSQL, the guarantee survives API restarts.

## 2. Prerequisites

| Tool | Needed for | Notes |
|---|---|---|
| Docker Desktop / Docker Engine with Compose v2 | everything | verified with Docker 29.4.3 |
| `bash`, `curl` | scripts | verified with macOS `/bin/bash` 3.2 |
| JDK 21 | `./mvnw` on the host (optional) | without a JDK, run Maven in Docker (below) |
| k6 | not needed | the load test runs the pinned `grafana/k6:1.8.1` image |
| `psql` | not needed | the verifier runs `psql` inside the Postgres container |

Default ports, all bound to `127.0.0.1` only:

| Stack | API | PostgreSQL |
|---|---|---|
| Main stack | `API_PORT` = 8080 | `POSTGRES_PORT` = 5432 |
| Recovery check | 18080 | 55433 |

If 5432 is already in use, prefix commands with `POSTGRES_PORT=55432`.

## 3. Start PostgreSQL and the API

```bash
docker compose up -d --build --wait      # builds the API image, waits for both healthchecks
docker compose ps                        # api and postgres should be "(healthy)"
```

The Compose project is named `idempotent-payment-ledger`. Data lives in the `idempotent-payment-ledger_ledger-data` volume.

- **Stop, keeping data:** `docker compose down`
- **Stop and delete this project's data:** `docker compose down -v`

**Alternative (API on the host):** `docker compose up -d postgres`, then `./mvnw spring-boot:run`.

## 4. Baseline tests

```bash
./mvnw clean verify
```

Without a local JDK, run the same command in a container. The socket mount lets Testcontainers start PostgreSQL:

```bash
docker run --rm -v "$PWD":/app -w /app -v ledger-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal eclipse-temurin:21-jdk ./mvnw -B clean verify
```

Expected: `BUILD SUCCESS` and `Failures: 0, Errors: 0`. The `Tests run` count changes as scenarios are added; use the number printed by that command, or the CI log.

## 5. Health and metrics

```bash
curl -s localhost:8080/actuator/health              # {"status":"UP", ... "db":{"status":"UP"} ...}
curl -s localhost:8080/actuator/health/readiness    # {"status":"UP"}
curl -s localhost:8080/actuator/prometheus | grep -E '^(ledger_|cta_commits_|http_server_requests_seconds_count)'
curl -s localhost:8080/actuator/metrics/http.server.requests   # JSON view of one metric
```

| Metric (Prometheus name) | Tags | Meaning |
|---|---|---|
| `http_server_requests_seconds_count` / `_sum` / `_max` | `method`, `uri` (templated, e.g. `/transactions/{id}/refund`), `status`, `outcome`, `exception` | request volume, total time, max latency; error rate = `outcome="CLIENT_ERROR"`/`"SERVER_ERROR"` over the total |
| `http_server_requests_seconds_bucket` | same + `le` | latency histogram (1 ms – 5 s) for `histogram_quantile(...)` |
| `ledger_transactions_total` | `type` = `payment`/`refund`, `outcome` = `posted`/`failed`/`replayed` | committed results of create/refund calls; `failed` = recorded business failure (e.g. insufficient funds); `replayed` = idempotent replay |
| `ledger_api_errors_total` | `code` (e.g. `IDEMPOTENCY_KEY_REUSED`, `TRANSACTION_ALREADY_REFUNDED`, `VALIDATION_FAILED`) | problem+json error responses by stable error code |
| `cta_commits_total` | `role` = `intra`/`payer`/`payee`, `outcome` = `committed`/`duplicate`/`rejected`/`declined`, `reason` = `decision.reason` (`OK`, `P1_EVENT_ALREADY_COMMITTED`, and the other commit reasons) | one count per durable commit decision. Ids, grant refs, accounts, and amounts are not tags. A rejected credit that also queues `pacs.002` is counted here. |
| standard JVM / HikariCP / Tomcat / process metrics | | provided by Spring Boot Actuator |

Design notes:
- Tags come only from small fixed sets. Ids, amounts and idempotency keys never appear in metrics; `MetricsIntegrationTest` asserts this.
- Ledger counters are incremented in the controller, after the service's transaction has committed, so a counted transaction is a durable one.
- The constraint-violation log line drops PostgreSQL's `Detail:` line, which would otherwise echo row values such as keys.
- `cta_commits_total` is incremented only after the decision's database transaction commits. A rolled-back race does not increment it.
- Console logs are JSON (`logging.structured.format.console=logstash`). Each durable decision is one INFO line, message `payment commit`, with `cta.role`, `decision.outcome` (enum name, for example `COMMITTED`), and `decision.reason` (the same token stored on `decision.reason` and copied to pacs.002 `StsRsnInf/Rsn/Prtry`). The line does not include account ids, amounts, or the attestation. `event_id` is logged only at DEBUG.

```json
{"message":"payment commit","level":"INFO","cta.role":"intra","decision.outcome":"COMMITTED","decision.reason":"OK"}
```

## 6. Load test

```bash
scripts/load-test.sh
```

This runs `load-test/ledger-load.js` in k6 (Docker) against `BASE_URL` (default `http://localhost:8080`), then runs `scripts/verify-ledger.sh <burst key>` against PostgreSQL. The script exits non-zero if any k6 check, any threshold or any database check fails.

| Variable | Default | Effect |
|---|---|---|
| `BASE_URL` | `http://localhost:${API_PORT:-8080}` | target API |
| `VUS` | 5 | virtual users in the `payments` scenario |
| `DURATION` | 30s | length of the `payments` scenario |
| `SLEEP_SECONDS` | 0.1 | pause per VU iteration (keeps the default load modest, about 50 req/s) |
| `BURST_SIZE` | 20 | concurrent identical requests sharing one Idempotency-Key |
| `REFUND_ITERATIONS` | 5 | sequential refund cycles |
| `P95_THRESHOLD_MS` | 1000 | sanity threshold for payment p95 latency (not an SLO) |

Scenarios, all run in parallel:
1. **`payments`**: unique-key payments. Each one checks the response: `201`, `POSTED`, a DEBIT on the source and a CREDIT on the destination for the exact amount. Every 10th payment is retried with the same key and must be a `200` replay with the same id.
2. **`idempotency_burst`**: `BURST_SIZE` identical requests sent concurrently with `http.batch`. The result must be exactly one `201`, `BURST_SIZE-1` × `200` replays, one distinct transaction id, and a stored transaction that is `POSTED` with 2 entries.
3. **`refunds`**: each cycle pays, refunds (`201`), retries the refund (`200` replay), sends a second refund with a new key (`409 TRANSACTION_ALREADY_REFUNDED`), and checks that the payment is `REFUNDED`.

**Database verification after k6** (`scripts/verify-ledger.sh`):
- The burst key has exactly 1 transaction, `POSTED`, with 2 entries whose debits equal credits.
- Globally there are:
  - no duplicate keys
  - no unbalanced transactions
  - no `POSTED`/`REFUNDED` transaction without exactly 2 entries
  - no `FAILED` transaction with entries
  - nothing stuck in `PENDING`
  - every account balance equals the sum of its entries
  - Σ debits = Σ credits

Results are written to `load-test/results/summary.txt` and `summary.json` (git-ignored).

Observed on the verification run (defaults, Apple-silicon laptop, Docker Desktop):

```
HTTP requests   total=1536  rate=51.0/s
                succeeded=1536  failed(unexpected status)=0
Latency, all    avg=9.10ms  p50=8.18ms  p(90)=15.22ms  p(95)=18.71ms  p(99)=35.11ms  max=100.10ms
Idempotency burst: 20 concurrent identical requests, one key
                   201 created=1  200 replayed=19  other=0  distinct transaction ids=1  => PASS
Checks: 5824 passed, 0 failed
Ledger verification: PASS
```

Latency numbers will differ on your machine. The pass/fail correctness results should not.

## 7. Restart / recovery check

```bash
scripts/recovery-check.sh            # KEEP_STACK=1 keeps the containers for inspection
```

The script uses its **own** Compose project, `ledger-recovery-check`, with its own network and volume on ports 18080/55433. It never touches the main stack. Every run starts with `down -v` of that project only, and the exit trap removes it again, so reruns are safe. It keeps the built image for faster reruns.

| Step | Action | Assertion |
|---|---|---|
| 1 | create accounts, `POST /transactions` with a known key | `201`, `POSTED`, `Idempotent-Replayed: false` |
| 2 | query PostgreSQL | 1 row for the key, 2 balanced entries, global invariants hold |
| 3 | `docker compose up -d --force-recreate --no-deps api` | new API container id, healthy |
| 4 | replay the identical request; `GET /transactions/{id}` | `200`, same id, `Idempotent-Replayed: true`; `POSTED` with 2 entries; DB still 1 row / 2 entries |
| 5 | `docker compose up -d --force-recreate --no-deps postgres` (same named volume) | new Postgres container id; the **same** API container reports `db: UP` again (Hikari reconnects) |
| 6 | replay again; new payment with a new key; check balance | `200` same id; `201 POSTED`; customer balance `76.5000` (75.50 counted once + 1.00); DB invariants hold |

On failure, the script names the step, prints `docker compose ps` and the recent API and Postgres logs, cleans up, and exits 1.

The automated counterpart in the Maven suite is `ApplicationRestartIntegrationTest`. It stops one application instance, starts a new one against the same database, and asserts the replay.

## 8. Invariants being demonstrated

| Invariant | Enforced by | Demonstrated by |
|---|---|---|
| **Duplicate-request protection**: one key gives one transaction and one posting, even under concurrency | `UNIQUE(idempotency_key)` + `INSERT … ON CONFLICT DO NOTHING` in the posting transaction; request fingerprint for key reuse | `IdempotencyIntegrationTest` (16 threads), `MetricsIntegrationTest` burst, k6 `idempotency_burst` + `verify-ledger.sh` |
| **Persistence across restart**: replay works after the API and the DB containers are replaced | key and result stored in PostgreSQL, data in a named volume | `ApplicationRestartIntegrationTest`, `scripts/recovery-check.sh` |
| **Balanced double-entry ledger** | deferred constraint trigger (debits = credits at commit), append-only trigger, CHECKs | `DatabaseConstraintsIntegrationTest`, `verify-ledger.sh` global checks after every load test / recovery step |

## 9. Interview demo (about 5–10 minutes)

1. **Start the stack (1 min).** Run `docker compose up -d --build --wait`, then `curl -s localhost:8080/actuator/health`. Point out the `db: UP` component and that the ports are bound to localhost.
2. **Show idempotency by hand (2 min).** Open Swagger at `localhost:8080/swagger-ui.html`, or use the curl examples in the README.
   - Create two accounts and a payment with `Idempotency-Key: demo-1`.
   - Send the same request again: you get `200` and `Idempotent-Replayed: true` with the same id.
   - Change the amount and keep the key: you get `422 IDEMPOTENCY_KEY_REUSED`.
3. **Load test (2 min).** Run `scripts/load-test.sh` and walk through the summary:
   - zero unexpected statuses
   - the latency percentiles
   - the burst line "201 created=1, 200 replayed=19, distinct ids=1"
   - the database verification, where every check passes
4. **Metrics (1 min).** Run `curl -s localhost:8080/actuator/prometheus | grep -E '^ledger_|_count\{.*uri="/transactions"'`.
   - The `posted` and `replayed` counters line up with the load test.
   - The HTTP 201/200/409 counts are broken down by templated URI.
   - No ids or keys appear in the metrics.
5. **Restart / recovery (2 min).** Run `scripts/recovery-check.sh`. The API container is replaced and the replay still returns the same transaction. Then Postgres is replaced, and the same API process reconnects with nothing lost.
6. **Close (1 min).** Name where correctness lives: the unique constraint, row locks, the deferred trigger. Then name what this does not show: real traffic, multiple instances behind a load balancer, auth, key expiry.

## 10. Commit-time authorization (single bank)

`docker compose up` (see §3) runs one API as institution `LOCAL`, `cta.role=INTRA`. Both customer accounts are in this database. `Idempotency-Key` on `POST /api/v1/payment-events` is transport metadata. The grant is the customer approval.

1. Create a funding account and two NGN customer accounts with `POST /accounts` (see the README examples; use `"currency":"NGN"`). Fund the payer from the funding account with `POST /transactions`.
2. Issue a grant. The response `authRef` is the approval id.

```bash
curl -s -D - localhost:8080/api/v1/grants -H 'Content-Type: application/json' \
  -d '{"payerAccountId":"<PAYER>","payeeAccountId":"<PAYEE>","amount":"10.00","currency":"NGN"}'
```

3. Enqueue the payment. This returns `202` and does not post. The worker calls `commitIntraBank`.

```bash
curl -s -D - localhost:8080/api/v1/payment-events -H 'Content-Type: application/json' \
  -d '{"eventId":"EVT-demo-1","authRef":"<AUTH_REF>","payerAccountId":"<PAYER>","payeeAccountId":"<PAYEE>","amount":"10.00","currency":"NGN","issuedAt":"2026-11-23T12:00:00Z"}'
```

4. Read the decision. The first row is `COMMITTED` / `OK`. The payer balance is 10.00 lower and the payee is 10.00 higher.

```bash
curl -s "localhost:8080/api/v1/decisions?eventId=EVT-demo-1"
curl -s localhost:8080/actuator/prometheus | grep 'cta_commits_total'
```

5. Sequential redelivery (S1): send the same `eventId` again. A second decision row is `DUPLICATE` / `P1_EVENT_ALREADY_COMMITTED`. There is still one ledger effect. `cta_commits_total{role="intra",outcome="duplicate",reason="P1_EVENT_ALREADY_COMMITTED"}` increases by one. The API log contains `"decision.reason":"P1_EVENT_ALREADY_COMMITTED"`.

`GET /api/v1/decisions?eventId=EVT-demo-1` with `Accept: application/xml` returns one pacs.002 for the latest decision. `Prtry` is that reason code.

## 11. Two banks

Generate a local attestation key pair first. The PEMs stay under `secrets/interbank-fixture/` and are gitignored. They are not production keys.

```powershell
.\scripts\generate-interbank-fixture.ps1
docker compose -f docker-compose.interbank.yml up -d --build --wait
```

| Process | Host port | `cta.role` | Database host port | What it does |
|---|---|---|---|---|
| `bank-a` | 8081 | `PAYER` | 5434 | Debits the payer, signs the attestation, relays `pacs.008` to Bank B |
| `bank-b` | 8082 | `PAYEE` | 5435 | Verifies the attestation and credits the payee once per `EndToEndId` |

`end_to_end_id` is the grant's `auth_ref`. Bank B does not read Bank A's grant table.

Smoke:

1. On Bank A (`localhost:8081`), create and fund an NGN payer. On Bank B (`localhost:8082`), create an NGN payee. Bank A's grant may name Bank B's payee account id; that id is not a row in Bank A's database.
2. `POST /api/v1/grants` on Bank A, then `POST /api/v1/payment-events` with `issuedAt` within the last 300 seconds. The outbox relay posts `pacs.008` to Bank B within about a second.
3. Bank A decisions for that `eventId` show `COMMITTED` / `OK`. Bank B decisions show `COMMITTED` / `OK` for the same end-to-end id. Each bank's `cta_commits_total` uses `role="payer"` or `role="payee"`.
4. Deliver the same `pacs.008` to Bank B again (`POST /api/v1/iso/pacs.008`, `Content-Type: application/xml`), including one with a new `MsgId`. Bank B records `DUPLICATE` / `P1_TRANSFER_ALREADY_CREDITED` or `P1_EVENT_ALREADY_COMMITTED` and does not credit again.

Stop the stack with `docker compose -f docker-compose.interbank.yml down`. Add `-v` to delete the two database volumes.

## 12. Evaluation workload

From this directory, the full five-seed run writes `../results-java.json` (or `results-java.json` here when this module is the git root):

```bash
./mvnw -B -Pcta-eval test
```

`./mvnw test` stays on the smaller smoke (seed 11) and does not replace that file. Copy counts from the JSON into the paper. Do not invent a throughput figure.

## 13. Security scans

CI (`.github/workflows/ci.yml`) runs three jobs, all with `contents: read`:

| Job | What it does |
|---|---|
| Maven verify | `./mvnw -B clean verify` (Testcontainers) |
| Gitleaks | `gitleaks/gitleaks-action@v2` on full git history. It does not scan gitignored `load-test/results/`. A license secret is required only when the repository belongs to a GitHub organization. |
| OWASP Dependency-Check | `./mvnw -Psecurity-audit -DskipTests org.owasp:dependency-check-maven:check`. Fails on CVSS 9 or higher. The first NVD sync without `NVD_API_KEY` can take most of an hour; later runs use the cached database. |

The same dependency scan locally:

```bash
./mvnw -B -Psecurity-audit -DskipTests org.owasp:dependency-check-maven:check
```

The HTML report is `target/dependency-check-report.html`. The profile is not part of a normal `./mvnw verify`.

## 14. Troubleshooting

| Symptom | Fix |
|---|---|
| `port is already allocated` | Another process uses the port. Set `POSTGRES_PORT` / `API_PORT`, or `RECOVERY_API_PORT` / `RECOVERY_POSTGRES_PORT` for the recovery check |
| `load-test: API is not reachable` | Start the stack first, or set `BASE_URL` |
| `verify-ledger: PostgreSQL … is not running` | The verifier needs the Compose Postgres. If the API runs elsewhere, point `COMPOSE_PROJECT` at the right project |
| k6 thresholds fail only on `p(95)` | The machine is overloaded. Lower `VUS` or raise `SLEEP_SECONDS`. The correctness checks are what matter |
| Payment event stays at `202` and no decision appears | The in-process worker is off when `cta.ingress.worker-enabled=false` (tests call `CommitWorker.drain`). In Compose the worker is on. Check the API log for `Commit worker dropped a command`. |
| Credit reason `P4_ATTESTATION_INVALID` | Bank B could not verify Bank A's signature, or the amount, currency, or accounts do not match the attestation. Regenerate `secrets/interbank-fixture/` and restart both banks so they load the same public key. |
| Log line `Outbox … was not delivered` | The relay could not POST `pacs.008` or `pacs.002`. Confirm the other bank is healthy and `CTA_HUB_PACS008_URL` / `CTA_HUB_PACS002_URL` point at it. The message stays unpublished and is retried. |
| Dependency-Check stalls on NVD | Set `NVD_API_KEY` (a free NVD API key) and rerun the profile. The data cache is `~/.m2/dependency-check-data`. |
