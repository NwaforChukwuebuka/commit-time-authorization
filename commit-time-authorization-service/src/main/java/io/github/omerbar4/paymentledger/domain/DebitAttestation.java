package io.github.omerbar4.paymentledger.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Bank A's signed proof that a debit committed. The signed bytes are the compact JSON of every
 * field except {@code signature}, in the order written by {@link #canonicalUnsigned()}.
 *
 * <p>{@code amountMinor} is the ledger amount at scale 4 ({@code 10.0000} is {@code 100000}),
 * not a currency's cash minor unit. {@code payloadHash} is {@code sha256:} plus {@link PayloadHasher}.
 */
public record DebitAttestation(
        int version,
        String endToEndId,
        String authRef,
        long amountMinor,
        String currency,
        String payerInstitutionId,
        String payeeInstitutionId,
        String cdtrAcctId,
        String payloadHash,
        String debitEventId,
        Instant consumedAt,
        String signature) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALGORITHM = "SHA256withECDSA";

    public static DebitAttestation issue(String payerAccountId, String endToEndId, String authRef, BigDecimal amount,
                                         String currency, String payerInstitutionId, String payeeInstitutionId,
                                         String cdtrAcctId, String debitEventId, Instant consumedAt) {
        BigDecimal normalized = Money.normalize(amount);
        String hash = "sha256:" + PayloadHasher.hash(payerAccountId, cdtrAcctId, normalized.toPlainString(), authRef);
        return new DebitAttestation(1, endToEndId, authRef, normalized.unscaledValue().longValueExact(), currency,
                payerInstitutionId, payeeInstitutionId, cdtrAcctId, hash, debitEventId,
                consumedAt.truncatedTo(ChronoUnit.SECONDS), null);
    }

    public DebitAttestation sign(PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(canonicalUnsigned().getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getEncoder().encodeToString(signature.sign());
            return new DebitAttestation(version, endToEndId, authRef, amountMinor, currency, payerInstitutionId,
                    payeeInstitutionId, cdtrAcctId, payloadHash, debitEventId, consumedAt, encoded);
        } catch (Exception ex) {
            throw new IllegalStateException("Debit attestation could not be signed", ex);
        }
    }

    public boolean verify(PublicKey publicKey) {
        if (version != 1 || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(canonicalUnsigned().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception ex) {
            return false;
        }
    }

    public String toJson() {
        if (signature == null) {
            return canonicalUnsigned();
        }
        return canonicalUnsigned().substring(0, canonicalUnsigned().length() - 1)
                + ",\"signature\":" + quote(signature) + "}";
    }

    public String canonicalUnsigned() {
        return "{"
                + "\"version\":" + version
                + ",\"endToEndId\":" + quote(endToEndId)
                + ",\"authRef\":" + quote(authRef)
                + ",\"amountMinor\":" + amountMinor
                + ",\"currency\":" + quote(currency)
                + ",\"payerInstitutionId\":" + quote(payerInstitutionId)
                + ",\"payeeInstitutionId\":" + quote(payeeInstitutionId)
                + ",\"cdtrAcctId\":" + quote(cdtrAcctId)
                + ",\"payloadHash\":" + quote(payloadHash)
                + ",\"debitEventId\":" + quote(debitEventId)
                + ",\"consumedAt\":" + quote(consumedAt.toString())
                + "}";
    }

    public static DebitAttestation parse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return new DebitAttestation(
                    node.path("version").asInt(),
                    text(node, "endToEndId"),
                    text(node, "authRef"),
                    node.path("amountMinor").asLong(),
                    text(node, "currency"),
                    text(node, "payerInstitutionId"),
                    text(node, "payeeInstitutionId"),
                    text(node, "cdtrAcctId"),
                    text(node, "payloadHash"),
                    text(node, "debitEventId"),
                    Instant.parse(text(node, "consumedAt")),
                    text(node, "signature"));
        } catch (RuntimeException | java.io.IOException ex) {
            throw new IllegalArgumentException("Debit attestation is not readable", ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Debit attestation is missing " + field);
        }
        return value.asText();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
