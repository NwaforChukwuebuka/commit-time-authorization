# Commit-time authorization service

Spring Boot reference implementation for commit-time authorization (CTA) on replayed payment events in digital banking.

This module began as a clone of [idempotent-payment-ledger](https://github.com/omerbar4/idempotent-payment-ledger) (MIT) and was reshaped for the thesis work. It lives in the parent workspace `commit-time-authorization/` next to local paper drafts and `results-java.json` (evaluation output from `./mvnw -Pcta-eval test`).

`iso-examples/` is a clone of [prowide-iso20022-examples](https://github.com/prowide/prowide-iso20022-examples). It is fixture XML only and is not a Maven module. ISO messages are parsed with the `pw-iso20022` dependency.

## What was kept

- Java 21, Spring Boot 3.5, PostgreSQL 16, Maven, Flyway, Docker Compose, Testcontainers
- `SELECT … FOR UPDATE` on the account rows, the conditional balance update, and one database transaction around the post
- Double-entry `ledger_entries`, the `accounts` and `transactions` tables, REST, OpenAPI, and the cloned payment, refund, lock, and constraint tests

## The idempotency key is not authorization

The cloned service treats `Idempotency-Key` as proof that a payment may be applied. The seminar already showed that this is not enough: a new message id can replay the same customer approval.

That rule is replaced by the grant. `CommitAuthorizationService.commitIntraBank` is the posting path for a payment event. It evaluates P1–P6 and sets the grant to `CONSUMED` in the same transaction as the balance change. A committed event is one row in `ledger` (`UNIQUE (event_id)`). Posting again with that `event_id` records `P1_EVENT_ALREADY_COMMITTED` and does not write a second row.

The cloned `transactions.idempotency_key` column is still there. On this path it stores `cta-event:` plus the event id, so the existing unique index is only a backstop for P1. It is not the customer approval. The HTTP `Idempotency-Key` on `POST /transactions` remains transport deduplication for that cloned API.

## Single bank

`docker compose up -d --build --wait` starts one API and one PostgreSQL. That process is institution `LOCAL` with `cta.role=INTRA`: both the payer and the payee live in this database. Health is `GET /actuator/health/readiness`.

Isolation for `commitIntraBank` is PostgreSQL `READ COMMITTED`, `SELECT … FOR UPDATE` on the grant and on both account rows, and `UNIQUE (ledger.event_id)`. A 16-thread burst of one event posts one ledger row, so the service does not raise the isolation level.

## Two banks

`docker compose -f docker-compose.interbank.yml up -d --build --wait` starts two copies of this image. `bank-a` is `cta.role=PAYER` with its own Postgres. `bank-b` is `cta.role=PAYEE` with a different Postgres. There is no second codebase.

Each bank has a local NGN settlement account that can go negative. The cloned ledger rejects a one-sided post, so Bank A moves money from the payer to settlement and Bank B moves money from settlement to the payee. The customer on the other side is not a row in this database. `auth_grant.payee_account_id` and the ledger effect's payer and payee columns are not foreign keys, so they can store that remote account id. `transactions` still references two local accounts.

Bank A signs a debit attestation (`SHA256withECDSA`, P-256) and puts the JSON in `pacs.008` `SplmtryData`. An outbox relay posts that XML to Bank B. Bank B credits once per `EndToEndId`. A second delivery, including one with a new `MsgId` and `InstrId`, does not credit again. A rejected credit is a `pacs.002`. Bank A sets the transfer to `REJECTED`. `POST /api/v1/transfers/{endToEndId}/reverse` posts one compensating refund. Calling it again does not post again.

Before the two-bank stack, generate a local attestation key pair (gitignored under `secrets/interbank-fixture/`):

```powershell
.\scripts\generate-interbank-fixture.ps1
```

That pair is only for local Compose; it is not a production key. `end_to_end_id` is the grant's `auth_ref`.

## `/api/v1`

| Method | Path | What it does |
| --- | --- | --- |
| `POST` | `/api/v1/grants` | Issue an `OPEN` grant. The service stores the payload hash. |
| `DELETE` | `/api/v1/grants/{authRef}` | Revoke the grant. |
| `POST` | `/api/v1/accounts/{id}/freeze` | Set the account to `FROZEN`. |
| `GET` | `/api/v1/accounts/{id}` | Balance and status. |
| `POST` | `/api/v1/payment-events` | Validate and enqueue. Returns `202`. Does not post and does not authorize. |
| `POST` | `/api/v1/iso/pain.001` | `application/xml` pain.001.001.11. Issues a grant and returns the JSON grant. |
| `POST` | `/api/v1/iso/pacs.008` | `application/xml` pacs.008.001.07. Enqueues a payment event and returns `202`. |
| `POST` | `/api/v1/iso/pacs.002` | `application/xml` pacs.002.001.10. Bank A applies the status to the transfer. |
| `POST` | `/api/v1/transfers/{endToEndId}/reverse` | Compensating refund after a rejected credit. A second call does not post. |
| `GET` | `/api/v1/decisions?eventId=` | Commit outcomes for that event. `Accept: application/xml` returns one pacs.002.001.10 for the latest decision. |

Field mapping for those three messages is in [docs/iso/README-mapping.md](docs/iso/README-mapping.md).

`POST /api/v1/payment-events` may carry `Idempotency-Key`. That header is transport metadata. It does not authorize the payment and it does not replace the grant. The handler puts a `PaymentEventCommand` on an in-process `BlockingQueue`. One virtual-thread worker calls `commitIntraBank`, `commitDebit`, or `commitCredit`, depending on `cta.role`. The queue is not Kafka. Publishing the same command twice is safe: P1 and, on a debit, the single-use grant allow one ledger row. On a credit, `EndToEndId` allows one ledger row. The queue is not durable if the process crashes.

The cloned routes `POST /accounts` and `POST /transactions` are unchanged.

## Paper evaluation workload

`./mvnw test` runs the scenario tests and one scaled CTA smoke (seed 11, 20 legitimate payments, 5 of each replay class).

`./mvnw -Pcta-eval test` runs seeds `11, 23, 37, 41, 59` at the full evaluation size and writes `../results-java.json` at the workspace root (or `results-java.json` here when the module is the repo root). CTA outcome counts are the acceptance criteria (`invalid_total` 0, `false_rejections` 0, and the reason histogram). Wall time and latency are recorded for the paper.

---

# Idempotent Payment Ledger API

A payment ledger REST API built with **Java 21, Spring Boot 3.5, PostgreSQL 16, Spring Data JPA and Flyway**. It records payments and refunds between accounts as double-entry postings. Its correctness guarantees are enforced by PostgreSQL itself (a unique constraint, row locks, CHECK constraints and a deferred trigger), not by in-memory checks. They are verified by Testcontainers integration tests, a k6 load test followed by a SQL invariant check, and a container restart/recovery script.

**Guarantees**

1. **A request is applied at most once.** Retries with the same `Idempotency-Key` never create a second transaction. The key is claimed with `INSERT … ON CONFLICT DO NOTHING` in the same database transaction as the ledger writes, so it survives API restarts.
2. **Concurrent duplicates collapse to one.** When identical requests race, exactly one creates the transaction and the rest replay it. Parallel payments never overdraw an account, and a payment can be refunded only once.
3. **The books always balance.** Every posted transaction writes an equal debit and credit, and PostgreSQL rejects any commit that breaks this.

**Quick start** (needs Docker only):

```bash
docker compose up -d --build --wait            # PostgreSQL + API on 127.0.0.1
curl -s localhost:8080/actuator/health          # {"status":"UP", ... "db":{"status":"UP"} ...}
docker compose down                             # stop (add -v to also delete this project's data)
```

Swagger UI is then at http://localhost:8080/swagger-ui.html. If port 5432 or 8080 is taken, set `POSTGRES_PORT` / `API_PORT`; see [Running locally](#running-locally).

**Documentation:** [RUNBOOK.md](RUNBOOK.md) covers operations, the load test, the recovery check and an interview demo. [docs/MEASUREMENT_REPORT.md](docs/MEASUREMENT_REPORT.md) has the reproducible test and latency evidence. [docs/PUBLISH_CHECKLIST.md](docs/PUBLISH_CHECKLIST.md) is the release checklist. [FINAL_PROJECT_REPORT.md](FINAL_PROJECT_REPORT.md) is the full engineering report.

> **Scope:** this is a portfolio project that has been validated locally and in containers. It is not deployed, not production-operated, and its load-test figures are not a capacity benchmark.

## Architecture

```
controller/   REST endpoints, header + Bean Validation, HTTP status mapping
service/      Use cases (payment, refund). One @Transactional method = one DB transaction
repository/   Spring Data JPA repositories + one JDBC fragment for INSERT ... ON CONFLICT
domain/       JPA entities (Account, LedgerTransaction, LedgerEntry), status/type enums, Money
dto/          Request/response records (amounts serialized as strings)
exception/    ApiException + GlobalExceptionHandler (RFC 9457 problem+json with a `code` field)
metrics/      LedgerMetrics: Micrometer counters for transaction outcomes and error codes
db/migration  Flyway schema: tables, CHECK/UNIQUE constraints, ledger triggers
```

Tables:

- **`accounts`**: currency, `balance NUMERIC(19,4)`, and `allow_negative_balance`. A funding or settlement account sets that flag; for every other account the `CHECK (allow_negative_balance OR balance >= 0)` constraint stops overdrafts.
- **`transactions`**: payments and refunds. `UNIQUE (idempotency_key)`, `amount > 0`, source ≠ destination, and a partial unique index that allows at most one non-failed refund per payment.
- **`ledger_entries`**: append-only DEBIT/CREDIT lines, one pair per posted transaction.

All money is `BigDecimal` in Java and `NUMERIC(19,4)` in PostgreSQL. The API accepts up to 4 decimal places and returns amounts as strings (`"12.3456"`), so JSON clients never convert them to floats.

## How idempotency works

`POST /transactions` and `POST /transactions/{id}/refund` both require an `Idempotency-Key` header. The key is stored on the transaction row, and a SHA-256 **fingerprint of the request** is stored next to it.

Each request runs in a single database transaction:

1. **Fast path.** If a row with this key already exists, compare fingerprints. If they match, return the stored transaction (`200 OK`, `Idempotent-Replayed: true`). If they differ, return `422 IDEMPOTENCY_KEY_REUSED`.
2. **Lock accounts.** Lock both accounts with `SELECT ... FOR UPDATE`, always in id order so that opposite-direction transfers can't deadlock.
3. **Claim the key atomically:**
   ```sql
   INSERT INTO transactions (...) VALUES (...) ON CONFLICT (idempotency_key) DO NOTHING
   ```
   If a concurrent request with the same key is in flight, PostgreSQL makes this insert **wait on the unique index** until the other transaction commits. It then inserts nothing (0 rows), and the request loads and returns the winner's row. Nothing is checked in memory; the unique constraint is the only arbiter.
4. **Post and commit.** Write the ledger entries, update the balances and set the status to `POSTED` (or `FAILED`), then commit.

Consequences:

- N concurrent identical requests return **one** transaction: one `201`, and `200` replays for the rest. The integration tests check this with 16 parallel HTTP calls.
- If the process crashes mid-request, everything rolls back, **including the key claim**, so a retry starts clean. No transaction is left stuck in `PENDING`.
- Requests rejected before step 3 (400 validation errors, 404 unknown account, 422 currency mismatch) don't consume the key.
- Business failures such as insufficient funds are **recorded** as `FAILED` transactions, so a retry replays the same outcome.
- A replay returns the transaction's *current* state. For example, after a refund, a replayed payment request shows `REFUNDED`.

Refunds follow the same pattern. They also lock the original payment row first, so concurrent refunds with *different* keys are serialized: exactly one succeeds and the others get `409 TRANSACTION_ALREADY_REFUNDED`.

## Double-entry ledger invariant

Each posted transaction writes exactly two entries: **DEBIT** the account the money leaves and **CREDIT** the account it goes to, for the same amount. An account's balance is `sum(credits) − sum(debits)`. The cached `accounts.balance` changes only in the same database transaction as its entries.

The invariant is enforced by the database, not only by the application:

| Rule | Mechanism |
|---|---|
| Debits = credits, single currency, per transaction | `DEFERRABLE INITIALLY DEFERRED` constraint trigger, checked at COMMIT |
| Entries can't be changed or removed | `BEFORE UPDATE OR DELETE` trigger. Corrections are new reversing entries (refunds) |
| Amounts are positive | `CHECK (amount > 0)` on transactions and entries |
| No overdraft on normal accounts | `CHECK (allow_negative_balance OR balance >= 0)` |
| One transaction per idempotency key | `UNIQUE (idempotency_key)` |
| At most one successful refund per payment | Partial unique index on `refund_of_id WHERE status <> 'FAILED'` |

### Statuses

| Status | Meaning |
|---|---|
| `PENDING` | Row inserted and key claimed. Exists only inside the creating DB transaction |
| `POSTED` | Entries written and balances updated |
| `FAILED` | Rejected by a business rule (`INSUFFICIENT_FUNDS`). No entries |
| `REFUNDED` | A posted payment that a `REFUND` transaction has fully reversed |

## API

Swagger UI: http://localhost:8080/swagger-ui.html · OpenAPI JSON: http://localhost:8080/v3/api-docs

| Method | Path | Description |
|---|---|---|
| `POST` | `/accounts` | Create an account |
| `GET` | `/accounts/{id}` | Account with its current balance |
| `POST` | `/transactions` | Create a payment (`Idempotency-Key` required) |
| `GET` | `/transactions/{id}` | Transaction with its ledger entries |
| `POST` | `/transactions/{id}/refund` | Fully refund a posted payment (`Idempotency-Key` required) |

### Examples

```bash
# A funding account (may go negative) and a customer wallet
curl -s localhost:8080/accounts -H 'Content-Type: application/json' \
  -d '{"name":"Funding","currency":"USD","allowNegativeBalance":true}'
curl -s localhost:8080/accounts -H 'Content-Type: application/json' \
  -d '{"name":"Alice","currency":"USD"}'

# Create a payment. Re-running this exact command returns the same transaction with 200 + Idempotent-Replayed: true
curl -i localhost:8080/transactions \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: order-1001' \
  -d '{"sourceAccountId":"<FUNDING_ID>","destinationAccountId":"<ALICE_ID>","amount":"100.00","currency":"USD","description":"Top-up"}'
```

```http
HTTP/1.1 201
Idempotent-Replayed: false
Location: /transactions/334a34c9-...

{"id":"334a34c9-...","type":"PAYMENT","status":"POSTED","amount":"100.0000","currency":"USD",
 "entries":[{"accountId":"<FUNDING_ID>","direction":"DEBIT","amount":"100.0000","currency":"USD"},
            {"accountId":"<ALICE_ID>","direction":"CREDIT","amount":"100.0000","currency":"USD"}], ...}
```

```bash
# Refund the payment
curl -i -X POST localhost:8080/transactions/<TX_ID>/refund -H 'Idempotency-Key: refund-order-1234'
```

Errors use `application/problem+json` with a stable `code`:

```json
{"title":"Unprocessable Entity","status":422,"detail":"Idempotency-Key was already used for a different request",
 "instance":"/transactions","code":"IDEMPOTENCY_KEY_REUSED"}
```

| HTTP | `code` |
|---|---|
| 400 | `VALIDATION_FAILED` (with `errors[]`), `MISSING_HEADER`, `SAME_ACCOUNT` |
| 404 | `ACCOUNT_NOT_FOUND`, `TRANSACTION_NOT_FOUND` |
| 409 | `TRANSACTION_ALREADY_REFUNDED`, `TRANSACTION_NOT_REFUNDABLE`, `CONSTRAINT_VIOLATION`, `CONCURRENT_MODIFICATION` |
| 422 | `IDEMPOTENCY_KEY_REUSED`, `CURRENCY_MISMATCH`, `TRANSACTION_NOT_REFUNDABLE` (refund of a refund) |

## Running locally

Requirements: Docker. A JDK 21 is needed only to run Maven on the host; the wrapper supplies Maven itself.

```bash
docker compose up -d --build --wait   # PostgreSQL 16 + the API, both published on 127.0.0.1 only
curl -s localhost:8080/actuator/health
```

To run the API from your IDE or with Maven instead, start only the database with `docker compose up -d postgres`, then run `./mvnw spring-boot:run`. Flyway migrates the schema on startup.

Ports are configurable: `POSTGRES_PORT` defaults to 5433 and `API_PORT` to 8080. Another Postgres on this machine already listens on 5432. Inside Compose the API still uses `postgres:5432`. A host-run API uses `jdbc:postgresql://localhost:5433/ledger`.

## Running tests

```bash
./mvnw clean verify
```

Continuous integration: [`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs the same command (JDK 21, Testcontainers on the runner's Docker) on every push and pull request.

The integration tests use **JUnit 5 + Testcontainers**. They start a throwaway `postgres:16-alpine` container (Docker must be running) and exercise the real HTTP API on a random port. They cover:

- **Idempotency:** a duplicate key returns the same transaction with no extra entries; the same key with a different body gets 422; `"10"` and `"10.0000"` count as the same request; a missing or blank key gets 400; `FAILED` results are replayed.
- **Concurrency:** 16 parallel payments with the same key produce exactly 1 transaction and 2 entries. 12 parallel refunds with the same key produce 1 refund. 10 parallel refunds with different keys produce 1 success and 9 conflicts. 20 parallel payments against a 100.00 balance produce exactly 10 posted, 10 failed, and a final balance of 0.
- **Ledger:** balanced entries; account balances equal the sum of their entries; the global sum of debits equals the sum of credits; refund reversal; refund rules.
- **Database constraints:** an unbalanced insert is rejected at commit; UPDATE and DELETE on entries are rejected; a duplicate key is rejected by `UNIQUE`; an overdraft is rejected by `CHECK`.
- **Metrics:** transaction counters by outcome, including a concurrent same-key burst (1 `posted`, the rest `replayed`); the Prometheus scrape contains HTTP and ledger metrics but no keys, ids or amounts; health shows the db component plus liveness and readiness probes.
- **Restart durability:** a payment is created by one application instance, that instance is shut down, and a new instance against the same database replays the request: same id, still 1 row and 2 entries.

## Operational validation

Three things are layered on top of the tests: metrics, a k6 load test, and a Docker restart/recovery check. **[RUNBOOK.md](RUNBOOK.md)** has the exact commands, expected output and a 5–10 minute demo sequence. Observed results and how to interpret them are in **[docs/MEASUREMENT_REPORT.md](docs/MEASUREMENT_REPORT.md)**.

| What | Where |
|---|---|
| Health and probes | `GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` |
| Prometheus metrics | `GET /actuator/prometheus`: `http_server_requests_seconds_*` (count, latency histogram, status/outcome) plus `ledger_transactions_total{type,outcome}` and `ledger_api_errors_total{code}` |
| Load test + DB verification | `scripts/load-test.sh` (k6 in Docker, then `scripts/verify-ledger.sh`) |
| Restart / recovery | `scripts/recovery-check.sh` (isolated Compose project; recreates the API and PostgreSQL containers) |

All of this is local validation. It is not a deployed service and not a capacity benchmark.

## Deliberate scope limits

No authentication or multi-tenancy, so idempotency keys are global. In production they would be scoped per client and expired after a retention window. Refunds are full refunds only, and there is no currency conversion. The actuator endpoints are unauthenticated and served on the application port, which suits local use only.

## License

[MIT](LICENSE) © 2026 Omer Bar
