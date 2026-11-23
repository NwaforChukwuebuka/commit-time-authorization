package io.github.omerbar4.paymentledger.service;

import io.github.omerbar4.paymentledger.config.CtaProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** One virtual thread. Pulls commands and calls {@link CommitAuthorizationService}. */
@Component
public class CommitWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CommitWorker.class);

    private final IngressQueue queue;
    private final CommitAuthorizationService commit;
    private final CtaProperties properties;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean running;
    private Thread thread;

    public CommitWorker(IngressQueue queue, CommitAuthorizationService commit, CtaProperties properties) {
        this.queue = queue;
        this.commit = commit;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (!properties.getIngress().isWorkerEnabled()) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("cta-commit").start(this::loop);
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

    /**
     * Posts every command currently queued. For tests that set {@code cta.ingress.worker-enabled=false}
     * so a freeze or a second publish can happen before commit.
     */
    public int drain() {
        if (started.get()) {
            throw new IllegalStateException(
                    "Commit worker is running; drain is only for cta.ingress.worker-enabled=false");
        }
        int posted = 0;
        PaymentEventCommand command;
        while ((command = queue.poll()) != null) {
            dispatch(command);
            posted++;
        }
        return posted;
    }

    private CommitResult dispatch(PaymentEventCommand command) {
        return switch (properties.getRole() == null ? "INTRA" : properties.getRole()) {
            case "PAYER" -> commit.commitDebit(command);
            case "PAYEE" -> commit.commitCredit(command);
            case "INTRA" -> commit.commitIntraBank(command);
            default -> throw new IllegalStateException("Unknown cta.role " + properties.getRole());
        };
    }

    private void loop() {
        while (running) {
            try {
                dispatch(queue.take());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                log.error("Commit worker dropped a command after a failed transaction", ex);
            }
        }
    }
}
