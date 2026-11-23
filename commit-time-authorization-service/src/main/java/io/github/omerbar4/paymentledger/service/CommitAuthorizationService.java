package io.github.omerbar4.paymentledger.service;

import io.github.omerbar4.paymentledger.config.CtaProperties;
import io.github.omerbar4.paymentledger.domain.Account;
import io.github.omerbar4.paymentledger.domain.AccountStatus;
import io.github.omerbar4.paymentledger.domain.AuthGrant;
import io.github.omerbar4.paymentledger.domain.CommitOutcome;
import io.github.omerbar4.paymentledger.domain.CommitReason;
import io.github.omerbar4.paymentledger.domain.Decision;
import io.github.omerbar4.paymentledger.domain.DebitAttestation;
import io.github.omerbar4.paymentledger.domain.GrantStatus;
import io.github.omerbar4.paymentledger.domain.Institution;
import io.github.omerbar4.paymentledger.domain.LedgerEffect;
import io.github.omerbar4.paymentledger.domain.LedgerEntry;
import io.github.omerbar4.paymentledger.domain.LedgerTransaction;
import io.github.omerbar4.paymentledger.domain.Money;
import io.github.omerbar4.paymentledger.domain.OutboxMessage;
import io.github.omerbar4.paymentledger.domain.PayloadHasher;
import io.github.omerbar4.paymentledger.domain.TransferCorrelation;
import io.github.omerbar4.paymentledger.exception.ApiException;
import io.github.omerbar4.paymentledger.iso.Pacs002Mapper;
import io.github.omerbar4.paymentledger.iso.Pacs008Mapper;
import io.github.omerbar4.paymentledger.metrics.CtaCommitMetrics;
import io.github.omerbar4.paymentledger.repository.AccountRepository;
import io.github.omerbar4.paymentledger.repository.AuthGrantRepository;
import io.github.omerbar4.paymentledger.repository.DecisionRepository;
import io.github.omerbar4.paymentledger.repository.InstitutionRepository;
import io.github.omerbar4.paymentledger.repository.LedgerEffectRepository;
import io.github.omerbar4.paymentledger.repository.LedgerEntryRepository;
import io.github.omerbar4.paymentledger.repository.LedgerTransactionRepository;
import io.github.omerbar4.paymentledger.repository.OutboxRepository;
import io.github.omerbar4.paymentledger.repository.TransferCorrelationRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The only component that changes a balance for a payment event.
 *
 * <p>Isolation is PostgreSQL {@code READ COMMITTED} plus {@code SELECT … FOR UPDATE} and the
 * unique indexes on {@code ledger.event_id} and {@code transfer_correlation.end_to_end_id}.
 * {@code commitIntraBank} posts both customer accounts. {@code commitDebit} and {@code commitCredit}
 * post the customer against this bank's settlement account. The cloned
 * {@code transactions.idempotency_key} is claimed with {@code cta-event:} plus the event id. That
 * column is not the customer authorization.
 */
@Service
public class CommitAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(CommitAuthorizationService.class);
    private static final String EVENT_KEY_PREFIX = "cta-event:";

    private final CtaProperties properties;
    private final CtaCommitMetrics commitMetrics;
    private final AuthGrantRepository grants;
    private final DecisionRepository decisions;
    private final LedgerEffectRepository ledger;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final AccountRepository accounts;
    private final InstitutionRepository institutions;
    private final TransferCorrelationRepository correlations;
    private final OutboxRepository outbox;
    private final AttestationService attestations;
    private final Pacs008Mapper pacs008;
    private final Pacs002Mapper pacs002;

    public CommitAuthorizationService(CtaProperties properties, CtaCommitMetrics commitMetrics,
                                      AuthGrantRepository grants,
                                      DecisionRepository decisions, LedgerEffectRepository ledger,
                                      LedgerTransactionRepository transactions, LedgerEntryRepository entries,
                                      AccountRepository accounts, InstitutionRepository institutions,
                                      TransferCorrelationRepository correlations, OutboxRepository outbox,
                                      AttestationService attestations, Pacs008Mapper pacs008, Pacs002Mapper pacs002) {
        this.properties = properties;
        this.commitMetrics = commitMetrics;
        this.grants = grants;
        this.decisions = decisions;
        this.ledger = ledger;
        this.transactions = transactions;
        this.entries = entries;
        this.accounts = accounts;
        this.institutions = institutions;
        this.correlations = correlations;
        this.outbox = outbox;
        this.attestations = attestations;
        this.pacs008 = pacs008;
        this.pacs002 = pacs002;
    }

    @Transactional
    public CommitResult commitIntraBank(PaymentEventCommand command) {
        BigDecimal amount = Money.normalize(command.amount());
        if (ledger.existsByEventId(command.eventId())) {
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_EVENT_ALREADY_COMMITTED);
        }
        if (stale(command)) {
            return record(command, CommitOutcome.REJECTED, CommitReason.P2_STALE_EVENT);
        }

        AuthGrant grant = grants.findByAuthRefForUpdate(command.authRef()).orElse(null);
        if (grant == null) {
            return record(command, CommitOutcome.REJECTED, CommitReason.P3_NO_GRANT);
        }
        if (ledger.existsByEventId(command.eventId())) {
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_EVENT_ALREADY_COMMITTED);
        }
        CommitReason grantReason = grantStatus(grant);
        if (grantReason != null) {
            return record(command, CommitOutcome.REJECTED, grantReason);
        }

        String eventHash = payloadHash(command, amount);
        if (mismatches(grant, command, amount, eventHash)) {
            return record(command, CommitOutcome.REJECTED, CommitReason.P4_PAYLOAD_MISMATCH);
        }

        Map<UUID, Account> locked = lockAccounts(command.payerAccountId(), command.payeeAccountId());
        Account payer = locked.get(command.payerAccountId());
        Account payee = locked.get(command.payeeAccountId());
        if (!payer.getCurrency().equals(command.currency()) || !payee.getCurrency().equals(command.currency())) {
            return record(command, CommitOutcome.REJECTED, CommitReason.P4_PAYLOAD_MISMATCH);
        }
        if (payer.getStatus() != AccountStatus.ACTIVE) {
            return record(command, CommitOutcome.REJECTED, CommitReason.P5_ACCOUNT_NOT_ACTIVE);
        }
        if (payer.getBalance().compareTo(amount) < 0) {
            return record(command, CommitOutcome.DECLINED, CommitReason.P6_INSUFFICIENT_FUNDS);
        }
        if (ledger.existsByEventId(command.eventId())) {
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_EVENT_ALREADY_COMMITTED);
        }
        if (!post(command, eventHash, payer, payee, amount, command.payerAccountId(), command.payeeAccountId())) {
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_EVENT_ALREADY_COMMITTED);
        }
        grant.consume(command.eventId());
        return record(command, CommitOutcome.COMMITTED, CommitReason.OK);
    }

    /** Payer bank. Consumes the grant, debits the payer into settlement, and queues one pacs.008. */
    @Transactional
    public CommitResult commitDebit(PaymentEventCommand command) {
        BigDecimal amount = Money.normalize(command.amount());
        if (ledger.existsByEventId(command.eventId())) {
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_EVENT_ALREADY_COMMITTED);
        }
        if (stale(command)) {
            return record(command, CommitOutcome.REJECTED, CommitReason.P2_STALE_EVENT);
        }

        AuthGrant grant = grants.findByAuthRefForUpdate(command.authRef()).orElse(null);
        if (grant == null) {
            return record(command, CommitOutcome.REJECTED, CommitReason.P3_NO_GRANT);
        }
        if (ledger.existsByEventId(command.eventId())) {
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_EVENT_ALREADY_COMMITTED);
        }
        CommitReason grantReason = grantStatus(grant);
        if (grantReason != null) {
            return record(command, CommitOutcome.REJECTED, grantReason);
        }

        String eventHash = payloadHash(command, amount);
        if (mismatches(grant, command, amount, eventHash)) {
            return record(command, CommitOutcome.REJECTED, CommitReason.P4_PAYLOAD_MISMATCH);
        }

        Account settlement = settlement();
        Map<UUID, Account> locked = lockAccounts(command.payerAccountId(), settlement.getId());
        Account payer = locked.get(command.payerAccountId());
        settlement = locked.get(settlement.getId());
        if (!payer.getCurrency().equals(command.currency()) || !settlement.getCurrency().equals(command.currency())) {
            return record(command, CommitOutcome.REJECTED, CommitReason.P4_PAYLOAD_MISMATCH);
        }
        if (payer.getStatus() != AccountStatus.ACTIVE) {
            return record(command, CommitOutcome.REJECTED, CommitReason.P5_ACCOUNT_NOT_ACTIVE);
        }
        if (payer.getBalance().compareTo(amount) < 0) {
            return record(command, CommitOutcome.DECLINED, CommitReason.P6_INSUFFICIENT_FUNDS);
        }
        if (ledger.existsByEventId(command.eventId())) {
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_EVENT_ALREADY_COMMITTED);
        }

        String attestation = attestations.sign(command, amount);
        if (!post(command, eventHash, payer, settlement, amount, command.payerAccountId(), command.payeeAccountId())) {
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_EVENT_ALREADY_COMMITTED);
        }
        grant.consume(command.eventId());
        correlations.save(TransferCorrelation.debited(command.authRef(), command.authRef(), command.eventId(), attestation));
        outbox.save(OutboxMessage.pending("PACS008", pacs008.toXml(command.withAttestation(attestation))));
        return record(command, CommitOutcome.COMMITTED, CommitReason.OK);
    }

    /**
     * Payee bank. No grant. One credit per {@code end_to_end_id}, checked inside this transaction.
     * A rejected credit stores a pacs.002 in the outbox and does not move money.
     */
    @Transactional
    public CommitResult commitCredit(PaymentEventCommand command) {
        BigDecimal amount = Money.normalize(command.amount());
        if (ledger.existsByEventId(command.eventId())) {
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_EVENT_ALREADY_COMMITTED);
        }
        if (correlations.findById(command.authRef()).isPresent()) {
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_TRANSFER_ALREADY_CREDITED);
        }
        if (stale(command)) {
            return rejectCredit(command, CommitReason.P2_STALE_EVENT);
        }

        Optional<DebitAttestation> attestation = attestations.verified(command, amount);
        if (attestation.isEmpty()) {
            return rejectCredit(command, CommitReason.P4_ATTESTATION_INVALID);
        }

        Account settlement = settlement();
        Map<UUID, Account> locked = lockAccounts(settlement.getId(), command.payeeAccountId());
        settlement = locked.get(settlement.getId());
        Account payee = locked.get(command.payeeAccountId());
        if (!payee.getCurrency().equals(command.currency()) || !settlement.getCurrency().equals(command.currency())) {
            return rejectCredit(command, CommitReason.P4_ATTESTATION_INVALID);
        }
        if (payee.getStatus() != AccountStatus.ACTIVE) {
            return rejectCredit(command, CommitReason.P5_ACCOUNT_NOT_ACTIVE);
        }
        if (ledger.existsByEventId(command.eventId())) {
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_EVENT_ALREADY_COMMITTED);
        }
        if (correlations.findById(command.authRef()).isPresent()) {
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_TRANSFER_ALREADY_CREDITED);
        }

        TransferCorrelation claimed = TransferCorrelation.credited(command.authRef(), attestation.get().authRef(),
                attestation.get().debitEventId(), command.eventId(), command.attestationJson());
        if (!correlations.insertIfAbsent(claimed)) {
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_TRANSFER_ALREADY_CREDITED);
        }
        String eventHash = attestation.get().payloadHash().startsWith("sha256:")
                ? attestation.get().payloadHash().substring("sha256:".length())
                : attestation.get().payloadHash();
        if (!post(command, eventHash, settlement, payee, amount, command.payerAccountId(), command.payeeAccountId())) {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus()
                    .setRollbackOnly();
            return record(command, CommitOutcome.DUPLICATE, CommitReason.P1_EVENT_ALREADY_COMMITTED);
        }
        return record(command, CommitOutcome.COMMITTED, CommitReason.OK);
    }

    private CommitResult rejectCredit(PaymentEventCommand command, CommitReason reason) {
        Decision decision = Decision.of(command.eventId(), command.authRef(), CommitOutcome.REJECTED, reason);
        decisions.save(decision);
        outbox.save(OutboxMessage.pending("PACS002", pacs002.toXml(decision)));
        observe(command, CommitOutcome.REJECTED, reason);
        return new CommitResult(CommitOutcome.REJECTED, reason);
    }

    private boolean post(PaymentEventCommand command, String eventHash, Account from, Account to, BigDecimal amount,
                         UUID ledgerPayer, UUID ledgerPayee) {
        LedgerTransaction candidate = LedgerTransaction.newPayment(EVENT_KEY_PREFIX + command.eventId(), eventHash,
                from.getId(), to.getId(), amount, command.currency(), command.kind());
        if (!transactions.insertIfAbsent(candidate)) {
            return false;
        }
        LedgerTransaction payment = transactions.findById(candidate.getId()).orElseThrow();
        entries.saveAll(LedgerEntry.transfer(payment.getId(), from, to, amount, command.currency()));
        payment.markPosted();
        ledger.save(LedgerEffect.posted(command.eventId(), command.authRef(), ledgerPayer, ledgerPayee, amount,
                command.currency(), command.kind(), payment.getId()));
        return true;
    }

    private Account settlement() {
        Institution institution = institutions.findById(properties.getInstitutionId())
                .orElseThrow(() -> new IllegalStateException("No institution " + properties.getInstitutionId()));
        if (institution.getSettlementAccountId() == null) {
            throw new IllegalStateException("Institution " + institution.getId() + " has no settlement account");
        }
        return accounts.findById(institution.getSettlementAccountId())
                .orElseThrow(() -> new IllegalStateException("Settlement account is missing"));
    }

    private boolean stale(PaymentEventCommand command) {
        return command.issuedAt().plusSeconds(properties.getFreshnessSeconds()).isBefore(command.now());
    }

    private static CommitReason grantStatus(AuthGrant grant) {
        if (grant.getStatus() == GrantStatus.CONSUMED) {
            return CommitReason.P3_GRANT_CONSUMED;
        }
        if (grant.getStatus() == GrantStatus.REVOKED) {
            return CommitReason.P3_GRANT_REVOKED;
        }
        return null;
    }

    private static boolean mismatches(AuthGrant grant, PaymentEventCommand command, BigDecimal amount, String eventHash) {
        return !grant.getPayerAccountId().equals(command.payerAccountId())
                || !grant.getPayeeAccountId().equals(command.payeeAccountId())
                || grant.getAmount().compareTo(amount) != 0
                || !grant.getCurrency().equals(command.currency())
                || !grant.getPayloadHash().equals(eventHash);
    }

    private static String payloadHash(PaymentEventCommand command, BigDecimal amount) {
        return PayloadHasher.hash(command.payerAccountId().toString(), command.payeeAccountId().toString(),
                amount.toPlainString(), command.authRef());
    }

    private CommitResult record(PaymentEventCommand command, CommitOutcome outcome, CommitReason reason) {
        decisions.save(Decision.of(command.eventId(), command.authRef(), outcome, reason));
        observe(command, outcome, reason);
        return new CommitResult(outcome, reason);
    }

    /**
     * Metrics and the commit log describe a decision that stayed committed. A rollback (lost race
     * on the ledger unique index) must not count or log that row.
     */
    private void observe(PaymentEventCommand command, CommitOutcome outcome, CommitReason reason) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(command.eventId(), outcome, reason);
                }
            });
            return;
        }
        publish(command.eventId(), outcome, reason);
    }

    private void publish(String eventId, CommitOutcome outcome, CommitReason reason) {
        commitMetrics.recordCommit(properties.getRole(), outcome, reason);
        log.atInfo()
                .addKeyValue("cta.role", CtaCommitMetrics.roleTag(properties.getRole()))
                .addKeyValue("decision.outcome", outcome.name())
                .addKeyValue("decision.reason", reason.name())
                .log("payment commit");
        log.atDebug()
                .addKeyValue("event_id", eventId)
                .addKeyValue("decision.reason", reason.name())
                .log("payment commit event recorded");
    }

    private Map<UUID, Account> lockAccounts(UUID first, UUID second) {
        Map<UUID, Account> locked = accounts.findAllByIdForUpdate(Set.of(first, second)).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        for (UUID id : List.of(first, second)) {
            if (!locked.containsKey(id)) {
                throw ApiException.notFound("ACCOUNT_NOT_FOUND", "Account " + id + " not found");
            }
        }
        return locked;
    }
}
