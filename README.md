# Commit-time authorization

Reference implementation of commit-time authorization for replayed payment events in digital banking. The service is Java 21, Spring Boot 3.5, PostgreSQL 16, and one Docker image.

A customer approval is a single-use grant. The grant is consumed in the same database transaction as the ledger post. An HTTP `Idempotency-Key` only identifies a message. It does not authorize the payment.

The code is in [`commit-time-authorization-service/`](commit-time-authorization-service/). That directory began as a clone of [idempotent-payment-ledger](https://github.com/omerbar4/idempotent-payment-ledger) (MIT). Its idempotency key was replaced by the grant. ISO messages are parsed with `pw-iso20022`. `iso-examples/` is fixture XML only.

## What a commit checks

| Deployment | Who posts | What stops a second posting |
| --- | --- | --- |
| One bank (`cta.role=INTRA`) | Both customer accounts in this database | P1–P6 and `UNIQUE (ledger.event_id)`. The grant is `CONSUMED` with the post. |
| Bank A (`cta.role=PAYER`) | Payer to this bank's settlement account | The same grant rules, then a signed debit attestation on `pacs.008`. |
| Bank B (`cta.role=PAYEE`) | Settlement to the payee | One credit per `EndToEndId`. Bank B does not read Bank A's grant table. |

A durable decision is one row in `decision` and one JSON log line with `decision.outcome` and `decision.reason`. Prometheus exports `cta_commits_total{role,outcome,reason}`.

## Run one bank

From `commit-time-authorization-service/`:

```bash
docker compose up -d --build --wait
curl -s localhost:8080/actuator/health/readiness
```

Swagger UI is at http://localhost:8080/swagger-ui.html. The API is `/api/v1` (grants, payment events, ISO ingress, decisions). `POST /accounts` and `POST /transactions` are the cloned ledger routes.

## Run two banks

```powershell
cd commit-time-authorization-service
.\scripts\generate-interbank-fixture.ps1
docker compose -f docker-compose.interbank.yml up -d --build --wait
```

Bank A is on port 8081, Bank B on 8082. Each has its own Postgres. The attestation keys under `secrets/interbank-fixture/` are local only and are not committed.

## Tests and evaluation

```bash
cd commit-time-authorization-service
./mvnw -B verify
./mvnw -B -Pcta-eval test
```

`verify` runs the Testcontainers suite. `cta-eval` runs seeds `11, 23, 37, 41, 59` and writes [`results-java.json`](results-java.json) at the repository root.

The workflow file is [`commit-time-authorization-service/.github/workflows/ci.yml`](commit-time-authorization-service/.github/workflows/ci.yml). It runs Maven verify, Gitleaks, and OWASP Dependency-Check when this service directory is the repository root. GitHub Actions on this repository reads workflows from the top-level `.github/workflows` directory only.

## Documentation

| Document | What it covers |
| --- | --- |
| [Service README](commit-time-authorization-service/README.md) | Grant rule, API table, cloned ledger behavior |
| [RUNBOOK.md](commit-time-authorization-service/RUNBOOK.md) | Single-bank flow, two-bank smoke, metrics, logs, scans |
| [ISO mapping](commit-time-authorization-service/docs/iso/README-mapping.md) | `pain.001`, `pacs.008`, `pacs.002` |

Actuator is unauthenticated and bound to localhost. This repository is local and container validation. It is not a deployed payment system.

## License

The service is [MIT](commit-time-authorization-service/LICENSE) © 2026 Omer Bar.
