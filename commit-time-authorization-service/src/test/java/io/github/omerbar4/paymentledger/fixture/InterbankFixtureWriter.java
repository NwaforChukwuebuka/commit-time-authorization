package io.github.omerbar4.paymentledger.fixture;

import io.github.omerbar4.paymentledger.domain.AttestationKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

/** Writes P-256 fixture PEMs for {@code docker-compose.interbank.yml} (never commit the output). */
public final class InterbankFixtureWriter {

    private InterbankFixtureWriter() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("secrets", "interbank-fixture");
        Files.createDirectories(dir);
        KeyPair keys = AttestationKeys.generate();
        Files.writeString(dir.resolve("payer-private.pem"), AttestationKeys.toPem(keys.getPrivate()));
        Files.writeString(dir.resolve("payer-public.pem"), AttestationKeys.toPem(keys.getPublic()));
        System.out.println("Wrote attestation fixture to " + dir.toAbsolutePath());
    }
}