# Publish Checklist

Steps to take before and right after making this repository public on GitHub. None of them have been performed yet: no remote exists, and nothing has been pushed.

## 1. Final local verification

- [ ] Working tree is clean and on the intended branch: `git status`, `git log --oneline -5`
- [ ] Full build and tests pass: `./mvnw -B clean verify` (or the containerised command in [MEASUREMENT_REPORT.md](MEASUREMENT_REPORT.md#3-automated-test-evidence)). Expect `Failures: 0, Errors: 0` and `BUILD SUCCESS`. The `Tests run` count is whatever that command prints.
- [ ] CI workflow lints cleanly: `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:1.7.12 -no-color`
- [ ] Optional, slower: `docker compose up -d --build --wait && scripts/load-test.sh`, then `scripts/recovery-check.sh`, then `docker compose down`

## 2. Secret and privacy scan before the first push

CI runs Gitleaks on every push and pull request (`.github/workflows/ci.yml`, job `gitleaks`). That job scans git history only. It does not see gitignored `load-test/results/`. The checklist below is an optional extra before the first push, useful when CI has not run yet.

- [ ] Optional extra: scan the full history and the tracked tree locally:

  ```bash
  docker run --rm -v "$PWD":/repo:ro -e HOME=/tmp --entrypoint sh zricethezav/gitleaks:v8.30.1 -c \
    'git config --global --add safe.directory /repo && gitleaks git /repo --redact --no-banner'
  ```

  Expected: `no leaks found`. The one reviewed false positive (a sample `Idempotency-Key` header in an early README) is listed by fingerprint in [`.gitleaksignore`](../.gitleaksignore).
- [ ] Do **not** run `gitleaks dir` on a working copy that contains `load-test/results/`. That folder holds git-ignored k6 output, including test idempotency keys that trigger the generic rule. Scan a clean checkout instead, e.g. `git clone . /tmp/ledger-scan`.
- [ ] Confirm nothing generated is tracked. This should print nothing:

  ```bash
  git ls-files | grep -E '^target/|load-test/results/(summary|burst)|\.env|\.DS_Store|\.idea/|\.vscode/'
  ```

- [ ] **Commit author email.** Existing commits record a personal email address as author and committer, and it becomes public on push. Decide before pushing:
  - Accept it, or
  - Set a GitHub `noreply` address for future commits (`git config user.email <id>+<user>@users.noreply.github.com`).
  - Rewriting the existing commits' author is a deliberate history rewrite; do it only before the first push, if at all.
  - If **Settings → Emails → "Block command line pushes that expose my email"** is enabled, GitHub will reject the push until this is resolved.
- [ ] Re-read `FINAL_PROJECT_REPORT.md` for content you'd rather keep private before it's public, for example its résumé-evidence sections.

## 3. Repository settings to enable (on GitHub, after creating the repository)

- [ ] Review **visibility** (private vs public) before the first push, and again before switching to public.
- [ ] **Settings → Security → Secret scanning: enable**, and enable **Push protection**.
- [ ] Keep **Actions → General → Workflow permissions** at the read-only default. The workflow itself requests only `contents: read`.
- [ ] Optional: enable Dependabot alerts for Maven dependencies.
- [ ] Optional: add a repository secret `NVD_API_KEY` so the Dependency-Check job is not limited to the anonymous NVD rate. The job still runs without it and fails only on CVSS 9+.

## 4. README and claims review

- [ ] The README describes local and containerized validation only. There must be no claims of deployment, production operation, uptime or capacity.
- [ ] Performance numbers appear only with their environment and the caveat in [MEASUREMENT_REPORT.md §7](MEASUREMENT_REPORT.md#7-how-to-interpret-p95).
- [ ] All data in docs, tests and scripts is test/demo data: synthetic accounts, amounts and keys. No real names, card data or bank data.
- [ ] Local-only credentials (`ledger`/`ledger` in `compose.yaml` and the `application.yml` defaults) are documented as demo values, and Actuator is described as localhost-only without access control.

## 5. CI status

- [x] A workflow exists locally: [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) (push / pull_request / workflow_dispatch, Ubuntu 24.04, `contents: read`). Jobs: Maven verify (Temurin 21, `./mvnw -B clean verify`), Gitleaks (`gitleaks/gitleaks-action@v2` on git history), and OWASP Dependency-Check (`./mvnw -Psecurity-audit -DskipTests org.owasp:dependency-check-maven:check`, fail at CVSS 9). The default `./mvnw verify` does not run the dependency scan.
- [ ] **GitHub-hosted CI has never run.** It can't be verified until a remote exists and a commit is pushed.

## 6. First-push verification

- [ ] After the first push, open **Actions → CI** and confirm:
  - `Maven verify (JDK 21, Testcontainers)` shows the `docker version` step succeeding and `BUILD SUCCESS` with `Failures: 0, Errors: 0`
  - `Gitleaks` succeeds (organization-owned repositories need a `GITLEAKS_LICENSE` secret; personal accounts do not)
  - `OWASP Dependency-Check` succeeds, or the report's CVSS 9+ findings are fixed before treating the branch as releasable
- [ ] If it fails, download the `surefire-reports` artifact uploaded by the failure step.
- [ ] Optional, only after a green run: add a CI status badge to the README using the real repository URL.
- [ ] Confirm that GitHub's secret-scanning tab shows no alerts.
