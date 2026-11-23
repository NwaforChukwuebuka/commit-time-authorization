package io.github.omerbar4.paymentledger.iso;

import com.prowidesoftware.swift.model.mx.dic.AccountIdentification4Choice;
import com.prowidesoftware.swift.model.mx.dic.CashAccount24;
import com.prowidesoftware.swift.model.mx.dic.CashAccount40;
import com.prowidesoftware.swift.model.mx.dic.GenericAccountIdentification1;
import java.util.UUID;

/** Ledger account ids travel in {@code Acct/Id/Othr/Id}. */
final class IsoAccounts {

    private IsoAccounts() {
    }

    static UUID require(AccountIdentification4Choice id, String field) {
        String raw = id == null || id.getOthr() == null ? null : id.getOthr().getId();
        if (raw == null || raw.isBlank()) {
            throw new IsoMappingException(field + " is required and must be the ledger account id");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IsoMappingException(field + " must be a ledger account UUID");
        }
    }

    static CashAccount40 cash40(UUID accountId) {
        return new CashAccount40().setId(other(accountId));
    }

    static CashAccount24 cash24(UUID accountId) {
        return new CashAccount24().setId(other(accountId));
    }

    private static AccountIdentification4Choice other(UUID accountId) {
        return new AccountIdentification4Choice()
                .setOthr(new GenericAccountIdentification1().setId(accountId.toString()));
    }
}
