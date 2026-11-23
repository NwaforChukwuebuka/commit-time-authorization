package io.github.omerbar4.paymentledger.iso;

/** The XML is not one credit transfer this service can turn into a grant or a payment event. */
public class IsoMappingException extends RuntimeException {

    public IsoMappingException(String message) {
        super(message);
    }
}
