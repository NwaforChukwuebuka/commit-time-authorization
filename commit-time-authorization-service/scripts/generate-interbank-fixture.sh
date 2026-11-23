#!/usr/bin/env bash
# Generates secrets/interbank-fixture/*.pem for docker-compose.interbank.yml (gitignored).
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=io.github.omerbar4.paymentledger.fixture.InterbankFixtureWriter