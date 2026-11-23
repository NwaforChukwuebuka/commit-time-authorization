package io.github.omerbar4.paymentledger.iso;

import com.prowidesoftware.swift.model.mx.dic.CreditTransferTransaction30;
import com.prowidesoftware.swift.model.mx.dic.SupplementaryData1;
import com.prowidesoftware.swift.model.mx.dic.SupplementaryDataEnvelope1;
import jakarta.xml.bind.JAXBElement;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * The debit attestation JSON rides in {@code CdtTrfTxInf/SplmtryData/Envlp}. {@code RmtInf/Strd}
 * is limited to 140 characters, which is shorter than a P-256 signature.
 */
final class AttestationXml {

    static final String NAMESPACE = "urn:mit8212:cta";
    private static final String LOCAL_NAME = "Attestation";

    private AttestationXml() {
    }

    static void embed(CreditTransferTransaction30 transaction, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            DocumentBuilderFactory factory = factory();
            Document document = factory.newDocumentBuilder().newDocument();
            Element element = document.createElementNS(NAMESPACE, LOCAL_NAME);
            element.setTextContent(json);
            transaction.addSplmtryData(new SupplementaryData1()
                    .setPlcAndNm(NAMESPACE)
                    .setEnvlp(new SupplementaryDataEnvelope1().setAny(element)));
        } catch (RuntimeException | javax.xml.parsers.ParserConfigurationException ex) {
            throw new IsoMappingException("debit attestation could not be written into pacs.008");
        }
    }

    static String fromTransaction(CreditTransferTransaction30 transaction) {
        if (transaction.getSplmtryData() == null) {
            return null;
        }
        for (SupplementaryData1 data : transaction.getSplmtryData()) {
            if (data.getEnvlp() == null) {
                continue;
            }
            String json = text(data.getEnvlp().getAny());
            if (json != null) {
                return json;
            }
        }
        return null;
    }

    static String fromXml(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = factory();
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList nodes = document.getElementsByTagNameNS(NAMESPACE, LOCAL_NAME);
            if (nodes.getLength() == 0) {
                nodes = document.getElementsByTagName(LOCAL_NAME);
            }
            if (nodes.getLength() == 0) {
                return null;
            }
            String text = nodes.item(0).getTextContent();
            return text == null || text.isBlank() ? null : text.trim();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(Object any) {
        if (any instanceof JAXBElement<?> element) {
            return text(element.getValue());
        }
        if (any instanceof Element element) {
            String value = element.getTextContent();
            return value == null || value.isBlank() ? null : value.trim();
        }
        if (any instanceof Node node) {
            String value = node.getTextContent();
            return value == null || value.isBlank() ? null : value.trim();
        }
        if (any instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        return null;
    }

    private static DocumentBuilderFactory factory() throws javax.xml.parsers.ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        return factory;
    }
}
