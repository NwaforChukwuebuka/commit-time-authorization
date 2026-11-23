# Generates secrets/interbank-fixture/*.pem for docker-compose.interbank.yml (gitignored).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
& .\mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java `
  "-Dexec.classpathScope=test" `
  "-Dexec.mainClass=io.github.omerbar4.paymentledger.fixture.InterbankFixtureWriter"