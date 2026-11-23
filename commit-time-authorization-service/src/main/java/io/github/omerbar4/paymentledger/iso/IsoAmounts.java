package io.github.omerbar4.paymentledger.iso;

import io.github.omerbar4.paymentledger.domain.Money;
import java.math.BigDecimal;

final class IsoAmounts {

    private IsoAmounts() {
    }

    static BigDecimal require(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IsoMappingException(field + " must be a positive amount");
        }
        try {
            return Money.normalize(value);
        } catch (ArithmeticException ex) {
            throw new IsoMappingException(field + " has more than 4 decimal places");
        }
    }

    static String currency(String ccy, String field) {
        if (ccy == null || !ccy.matches("[A-Z]{3}")) {
            throw new IsoMappingException(field + " must be a 3-letter currency code");
        }
        return ccy;
    }
}
