package io.github.omerbar4.paymentledger.repository;

import io.github.omerbar4.paymentledger.domain.TransferCorrelation;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code ON CONFLICT DO NOTHING} so two credit commits of one {@code end_to_end_id} do not throw.
 * The loser keeps a usable transaction and records a duplicate instead of a second posting.
 */
class TransferCorrelationInsertImpl implements TransferCorrelationInsert {

    private static final String SQL = """
            INSERT INTO transfer_correlation (end_to_end_id, auth_ref, debit_event_id, credit_event_id, state, attestation)
            VALUES (:endToEndId, :authRef, :debitEventId, :creditEventId, :state, :attestation)
            ON CONFLICT (end_to_end_id) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    TransferCorrelationInsertImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(TransferCorrelation correlation) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("endToEndId", correlation.getEndToEndId())
                .addValue("authRef", correlation.getAuthRef())
                .addValue("debitEventId", correlation.getDebitEventId())
                .addValue("creditEventId", correlation.getCreditEventId())
                .addValue("state", correlation.getState().name())
                .addValue("attestation", correlation.getAttestation());
        return jdbc.update(SQL, params) == 1;
    }
}
