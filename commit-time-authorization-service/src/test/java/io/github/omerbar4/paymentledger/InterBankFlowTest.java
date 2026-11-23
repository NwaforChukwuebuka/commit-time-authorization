package io.github.omerbar4.paymentledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.omerbar4.paymentledger.domain.AttestationKeys;
import io.github.omerbar4.paymentledger.iso.Pacs008Mapper;
import io.github.omerbar4.paymentledger.service.CommitAuthorizationService;
import io.github.omerbar4.paymentledger.service.PaymentEventCommand;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Two databases, one service class. Bank A debits once. Bank B is offered the same pacs.008 twice
 * and then a copy with a new message id.
 */
class InterBankFlowTest {

    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    @Test
    void doubleDeliveryCreditsOnceAndARejectedCreditCanBeReversed() throws Exception {
        KeyPair keys = AttestationKeys.generate();
        String privatePem = AttestationKeys.toPem(keys.getPrivate());
        String publicPem = AttestationKeys.toPem(keys.getPublic());
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))) {
            postgres.start();
            createDatabase(postgres, "ledger_a");
            createDatabase(postgres, "ledger_b");
            try (ConfigurableApplicationContext bankA = start(postgres, "ledger_a", payerProperties(privatePem, publicPem));
                 ConfigurableApplicationContext bankB = start(postgres, "ledger_b", payeeProperties(publicPem))) {
                exercise(bankA, bankB);
            }
        }
    }

    private static void exercise(ConfigurableApplicationContext bankA, ConfigurableApplicationContext bankB) {
        RestClient http = RestClient.create();
        String baseA = base(bankA);
        String baseB = base(bankB);
        JdbcTemplate jdbcA = bankA.getBean(JdbcTemplate.class);
        JdbcTemplate jdbcB = bankB.getBean(JdbcTemplate.class);

        UUID payee = createAccount(http, baseB, false);
        UUID payer = fund(http, baseA, "100.00");
        String authRef = grant(http, baseA, payer, payee);
        String eventId = "EVT-" + UUID.randomUUID();
        Instant now = Instant.now();
        bankA.getBean(CommitAuthorizationService.class).commitDebit(new PaymentEventCommand(eventId, authRef, payer,
                payee, AMOUNT, "NGN", now.minusSeconds(5), now, "LEGIT"));
        String xml = jdbcA.queryForObject("select payload from outbox where kind = 'PACS008' and payload like ?",
                String.class, "%" + eventId + "%");

        postXml(http, baseB + "/api/v1/iso/pacs.008", xml);
        postXml(http, baseB + "/api/v1/iso/pacs.008", xml);
        await().atMost(Duration.ofSeconds(20)).until(() -> count(jdbcB,
                "select count(*) from decision where event_id = ?", eventId) >= 2);
        assertThat(count(jdbcB, "select count(*) from ledger where auth_ref = ?", authRef)).isEqualTo(1);
        assertThat(balance(http, baseB, payee)).isEqualByComparingTo("10.00");
        assertThat(balance(http, baseA, payer)).isEqualByComparingTo("90.00");

        Pacs008Mapper mapper = bankA.getBean(Pacs008Mapper.class);
        PaymentEventCommand parsed = mapper.toCommand(xml, now);
        String rekeyed = mapper.toXml(new PaymentEventCommand("EVT-REKEY-" + UUID.randomUUID(), parsed.authRef(),
                parsed.payerAccountId(), parsed.payeeAccountId(), parsed.amount(), parsed.currency(),
                parsed.issuedAt(), now, parsed.kind(), parsed.attestationJson()));
        postXml(http, baseB + "/api/v1/iso/pacs.008", rekeyed);
        await().atMost(Duration.ofSeconds(20)).until(() -> count(jdbcB,
                "select count(*) from decision where reason = 'P1_TRANSFER_ALREADY_CREDITED' and auth_ref = ?",
                authRef) >= 1);
        assertThat(balance(http, baseB, payee)).isEqualByComparingTo("10.00");

        UUID frozen = createAccount(http, baseB, false);
        http.post().uri(baseB + "/api/v1/accounts/" + frozen + "/freeze").retrieve().toBodilessEntity();
        String rejectedRef = grant(http, baseA, payer, frozen);
        String rejectedEvent = "EVT-" + UUID.randomUUID();
        Instant later = Instant.now();
        bankA.getBean(CommitAuthorizationService.class).commitDebit(new PaymentEventCommand(rejectedEvent, rejectedRef,
                payer, frozen, AMOUNT, "NGN", later.minusSeconds(5), later, "LEGIT"));
        String rejectedXml = jdbcA.queryForObject(
                "select payload from outbox where kind = 'PACS008' and payload like ?", String.class,
                "%" + rejectedEvent + "%");
        postXml(http, baseB + "/api/v1/iso/pacs.008", rejectedXml);
        await().atMost(Duration.ofSeconds(20)).until(() -> count(jdbcB,
                "select count(*) from decision where event_id = ? and reason = 'P5_ACCOUNT_NOT_ACTIVE'",
                rejectedEvent) == 1);
        String pacs002 = jdbcB.queryForObject(
                "select payload from outbox where kind = 'PACS002' and payload like ? order by created_at desc limit 1",
                String.class, "%" + rejectedRef + "%");
        postXml(http, baseA + "/api/v1/iso/pacs.002", pacs002);
        assertThat(jdbcA.queryForObject("select state from transfer_correlation where end_to_end_id = ?", String.class,
                rejectedRef)).isEqualTo("REJECTED");
        assertThat(balance(http, baseA, payer)).isEqualByComparingTo("80.00");

        http.post().uri(baseA + "/api/v1/transfers/" + rejectedRef + "/reverse").retrieve().toBodilessEntity();
        assertThat(balance(http, baseA, payer)).isEqualByComparingTo("90.00");
        assertThat(jdbcA.queryForObject("select state from transfer_correlation where end_to_end_id = ?", String.class,
                rejectedRef)).isEqualTo("REVERSED");
        http.post().uri(baseA + "/api/v1/transfers/" + rejectedRef + "/reverse").retrieve().toBodilessEntity();
        assertThat(balance(http, baseA, payer)).isEqualByComparingTo("90.00");
        assertThat(balance(http, baseB, frozen)).isEqualByComparingTo("0");
    }

    private static ConfigurableApplicationContext start(PostgreSQLContainer<?> postgres, String database,
                                                        Map<String, Object> role) {
        java.util.HashMap<String, Object> properties = new java.util.HashMap<>(role);
        properties.put("server.port", "0");
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.datasource.url", url(postgres, database));
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        SpringApplicationBuilder builder = new SpringApplicationBuilder(LedgerApplication.class);
        builder.application().addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("interbank", properties)));
        return builder.run();
    }

    private static Map<String, Object> payerProperties(String privatePem, String publicPem) {
        return Map.of(
                "cta.role", "PAYER",
                "cta.institution-id", "BANKA",
                "cta.counterparty-institution-id", "BANKB",
                "cta.attestation.private-key-pem", privatePem,
                "cta.attestation.payer-public-key-pem", publicPem);
    }

    private static Map<String, Object> payeeProperties(String publicPem) {
        return Map.of(
                "cta.role", "PAYEE",
                "cta.institution-id", "BANKB",
                "cta.attestation.payer-public-key-pem", publicPem);
    }

    private static void createDatabase(PostgreSQLContainer<?> postgres, String name) throws Exception {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
    }

    private static String url(PostgreSQLContainer<?> postgres, String database) {
        String jdbc = postgres.getJdbcUrl();
        return jdbc.substring(0, jdbc.lastIndexOf('/') + 1) + database;
    }

    private static String base(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    private static UUID createAccount(RestClient http, String base, boolean allowNegative) {
        JsonNode body = http.post().uri(base + "/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "acct-" + UUID.randomUUID(), "currency", "NGN", "allowNegativeBalance", allowNegative))
                .retrieve()
                .body(JsonNode.class);
        return UUID.fromString(body.get("id").asText());
    }

    private static UUID fund(RestClient http, String base, String amount) {
        UUID funding = createAccount(http, base, true);
        UUID account = createAccount(http, base, false);
        http.post().uri(base + "/transactions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sourceAccountId", funding, "destinationAccountId", account, "amount", amount, "currency", "NGN"))
                .retrieve()
                .toBodilessEntity();
        return account;
    }

    private static String grant(RestClient http, String base, UUID payer, UUID payee) {
        JsonNode body = http.post().uri(base + "/api/v1/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("payerAccountId", payer, "payeeAccountId", payee, "amount", "10.00", "currency", "NGN"))
                .retrieve()
                .body(JsonNode.class);
        return body.get("authRef").asText();
    }

    private static void postXml(RestClient http, String url, String xml) {
        http.post().uri(url).contentType(MediaType.APPLICATION_XML).body(xml).retrieve().toBodilessEntity();
    }

    private static BigDecimal balance(RestClient http, String base, UUID accountId) {
        JsonNode body = http.get().uri(base + "/accounts/" + accountId).retrieve().body(JsonNode.class);
        return new BigDecimal(body.get("balance").asText());
    }

    private static int count(JdbcTemplate jdbc, String sql, Object arg) {
        Integer count = jdbc.queryForObject(sql, Integer.class, arg);
        return count == null ? 0 : count;
    }
}
