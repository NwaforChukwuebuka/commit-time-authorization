package io.github.omerbar4.paymentledger.iso;

import java.util.List;

final class IsoMessages {

    private IsoMessages() {
    }

    static void requireSingleCount(String nbOfTxs, String where) {
        if (nbOfTxs != null && !nbOfTxs.isBlank() && !"1".equals(nbOfTxs.trim())) {
            throw new IsoMappingException(where + " NbOfTxs must be 1");
        }
    }

    static void requireOne(List<?> items, String where) {
        if (items == null || items.size() != 1) {
            throw new IsoMappingException(where + " must contain exactly one credit transfer");
        }
    }

    static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
