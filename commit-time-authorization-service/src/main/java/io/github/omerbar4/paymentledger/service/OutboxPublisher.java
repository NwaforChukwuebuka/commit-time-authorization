package io.github.omerbar4.paymentledger.service;

import io.github.omerbar4.paymentledger.config.CtaProperties;
import io.github.omerbar4.paymentledger.domain.OutboxMessage;
import io.github.omerbar4.paymentledger.repository.OutboxRepository;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/** Posts one outbox row and marks it published in the same transaction as the mark. */
@Service
public class OutboxPublisher {

    private final OutboxRepository outbox;
    private final CtaProperties properties;
    private final RestClient http = RestClient.create();

    public OutboxPublisher(OutboxRepository outbox, CtaProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    @Transactional
    public boolean publish(UUID id) {
        OutboxMessage message = outbox.findById(id).orElse(null);
        if (message == null || message.getPublishedAt() != null) {
            return false;
        }
        String url = urlFor(message.getKind());
        if (url == null || url.isBlank()) {
            return false;
        }
        http.post().uri(url).contentType(MediaType.APPLICATION_XML).body(message.getPayload()).retrieve().toBodilessEntity();
        message.markPublished();
        return true;
    }

    private String urlFor(String kind) {
        return switch (kind) {
            case "PACS008" -> properties.getHub().getPacs008Url();
            case "PACS002" -> properties.getHub().getPacs002Url();
            default -> null;
        };
    }
}
