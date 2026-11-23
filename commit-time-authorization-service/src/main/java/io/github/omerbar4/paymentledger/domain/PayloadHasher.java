package io.github.omerbar4.paymentledger.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of {@code payer|payee|amount|authRef}, the same field order as {@code phash} in
 * {@code cta_experiment.py}. Amount is the normalized ledger plain string ({@code scale 4}),
 * because this service stores money as {@code NUMERIC(19,4)} rather than integer minor units.
 */
public final class PayloadHasher {

    private PayloadHasher() {
    }

    public static String hash(String payer, String payee, String amount, String authRef) {
        String canonical = payer + "|" + payee + "|" + amount + "|" + authRef;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
