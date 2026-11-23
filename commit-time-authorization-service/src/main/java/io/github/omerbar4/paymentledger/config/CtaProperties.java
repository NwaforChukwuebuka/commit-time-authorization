package io.github.omerbar4.paymentledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cta")
public class CtaProperties {

    /** P2 window. Matches {@code FRESHNESS} in the Python prototype. */
    private int freshnessSeconds = 300;

    /** This process's institution. {@code LOCAL} for the single-bank item 8 deployment. */
    private String institutionId = "LOCAL";

    /**
     * {@code INTRA} posts both sides in this database. {@code PAYER} debits and signs.
     * {@code PAYEE} verifies the attestation and credits. The same image runs all three.
     */
    private String role = "INTRA";

    /** The other bank, written into the debit attestation when this process is {@code PAYER}. */
    private String counterpartyInstitutionId;

    private Attestation attestation = new Attestation();

    private Hub hub = new Hub();

    private Ingress ingress = new Ingress();

    public int getFreshnessSeconds() {
        return freshnessSeconds;
    }

    public void setFreshnessSeconds(int freshnessSeconds) {
        this.freshnessSeconds = freshnessSeconds;
    }

    public String getInstitutionId() {
        return institutionId;
    }

    public void setInstitutionId(String institutionId) {
        this.institutionId = institutionId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getCounterpartyInstitutionId() {
        return counterpartyInstitutionId;
    }

    public void setCounterpartyInstitutionId(String counterpartyInstitutionId) {
        this.counterpartyInstitutionId = counterpartyInstitutionId;
    }

    public Attestation getAttestation() {
        return attestation;
    }

    public void setAttestation(Attestation attestation) {
        this.attestation = attestation;
    }

    public Hub getHub() {
        return hub;
    }

    public void setHub(Hub hub) {
        this.hub = hub;
    }

    public Ingress getIngress() {
        return ingress;
    }

    public void setIngress(Ingress ingress) {
        this.ingress = ingress;
    }

    public static class Attestation {

        /** Payer signing key. Raw PEM, {@code classpath:}, or {@code file:}. */
        private String privateKeyPem;

        /** Payer public key. Bank B stores this on {@code institution} and verifies with it. */
        private String payerPublicKeyPem;

        public String getPrivateKeyPem() {
            return privateKeyPem;
        }

        public void setPrivateKeyPem(String privateKeyPem) {
            this.privateKeyPem = privateKeyPem;
        }

        public String getPayerPublicKeyPem() {
            return payerPublicKeyPem;
        }

        public void setPayerPublicKeyPem(String payerPublicKeyPem) {
            this.payerPublicKeyPem = payerPublicKeyPem;
        }
    }

    public static class Hub {

        /** Where this payer posts {@code pacs.008}. In Compose that is Bank B. */
        private String pacs008Url;

        /** Where this payee posts {@code pacs.002}. In Compose that is Bank A. */
        private String pacs002Url;

        public String getPacs008Url() {
            return pacs008Url;
        }

        public void setPacs008Url(String pacs008Url) {
            this.pacs008Url = pacs008Url;
        }

        public String getPacs002Url() {
            return pacs002Url;
        }

        public void setPacs002Url(String pacs002Url) {
            this.pacs002Url = pacs002Url;
        }
    }

    public static class Ingress {

        /** When false, tests enqueue and then call {@code CommitWorker.drain}. */
        private boolean workerEnabled = true;

        public boolean isWorkerEnabled() {
            return workerEnabled;
        }

        public void setWorkerEnabled(boolean workerEnabled) {
            this.workerEnabled = workerEnabled;
        }
    }
}
