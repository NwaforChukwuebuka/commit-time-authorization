package io.github.omerbar4.paymentledger.service;

import io.github.omerbar4.paymentledger.config.CtaProperties;
import io.github.omerbar4.paymentledger.domain.OutboxMessage;
import io.github.omerbar4.paymentledger.repository.OutboxRepository;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Hands unpublished pacs.008 and pacs.002 messages to the configured hub URL. */
@Component
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final OutboxPublisher publisher;
    private final CtaProperties properties;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean running;
    private Thread thread;

    public OutboxRelay(OutboxRepository outbox, OutboxPublisher publisher, CtaProperties properties) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (!configured()) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("cta-outbox").start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        started.set(false);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private boolean configured() {
        String pacs008 = properties.getHub().getPacs008Url();
        String pacs002 = properties.getHub().getPacs002Url();
        return (pacs008 != null && !pacs008.isBlank()) || (pacs002 != null && !pacs002.isBlank());
    }

    private void loop() {
        while (running) {
            boolean published = false;
            try {
                for (OutboxMessage message : outbox.findTop20ByPublishedAtIsNullOrderByCreatedAtAsc()) {
                    if (!running) {
                        return;
                    }
                    try {
                        if (publisher.publish(message.getId())) {
                            published = true;
                        }
                    } catch (RuntimeException ex) {
                        log.warn("Outbox {} was not delivered", message.getId(), ex);
                    }
                }
                if (!published) {
                    Thread.sleep(250);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                log.warn("Outbox relay failed while reading unpublished messages", ex);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
