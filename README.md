# Commit-time authorization

Local workspace for the MIT 8212 paper *Development and Evaluation of a Commit-Time Authorization Model for Replayed Payment Events in Digital Banking*.

| Path | Purpose |
| --- | --- |
| [`commit-time-authorization-service/`](commit-time-authorization-service/) | Public Spring Boot service (git repo): grants, P1–P6, PostgreSQL, ISO 20022, Docker |
| `results-java.json` | Evaluation output from `./mvnw -Pcta-eval test` in the service directory |
| `plan.md`, paper drafts, `info/` | Local only (see root `.gitignore`) |

Open **`commit-time-authorization-service`** in the IDE when working on code; use this folder when writing the paper alongside evaluation artifacts. Operations (metrics, structured logs, security scans, and the inter-bank smoke) are in [`commit-time-authorization-service/RUNBOOK.md`](commit-time-authorization-service/RUNBOOK.md).