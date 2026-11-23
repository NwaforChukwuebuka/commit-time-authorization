package io.github.omerbar4.paymentledger.domain;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** P-256 keys in PKCS#8 / X.509 PEM. Signing uses {@code SHA256withECDSA}. */
public final class AttestationKeys {

    private static final String ALGORITHM = "SHA256withECDSA";

    private AttestationKeys() {
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("P-256 key generation failed", ex);
        }
    }

    public static String toPem(PrivateKey key) {
        return pem("PRIVATE KEY", key.getEncoded());
    }

    public static String toPem(PublicKey key) {
        return pem("PUBLIC KEY", key.getEncoded());
    }

    public static PrivateKey readPrivate(String pem) {
        try {
            return KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(decode(pem, "PRIVATE KEY")));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalArgumentException("Attestation private key is not a P-256 PKCS#8 PEM", ex);
        }
    }

    public static PublicKey readPublic(String pem) {
        try {
            return KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(decode(pem, "PUBLIC KEY")));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalArgumentException("Attestation public key is not a P-256 X.509 PEM", ex);
        }
    }

    /** Rejects a configured pair that cannot verify its own signature. */
    public static void requirePair(String privatePem, String publicPem) {
        PrivateKey privateKey = readPrivate(privatePem);
        PublicKey publicKey = readPublic(publicPem);
        byte[] probe = "cta-key-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(probe);
            byte[] signed = signature.sign();
            signature.initVerify(publicKey);
            signature.update(probe);
            if (!signature.verify(signed)) {
                throw new IllegalStateException("Attestation private key does not match the payer public key");
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Attestation key pair could not be checked", ex);
        }
    }

    private static String pem(String label, byte[] encoded) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    private static byte[] decode(String pem, String label) {
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int start = pem.indexOf(begin);
        int stop = pem.indexOf(end);
        if (start < 0 || stop < 0) {
            throw new IllegalArgumentException("PEM is missing " + label);
        }
        String body = pem.substring(start + begin.length(), stop).replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
