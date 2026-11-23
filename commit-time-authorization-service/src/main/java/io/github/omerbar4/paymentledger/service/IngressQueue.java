package io.github.omerbar4.paymentledger.service;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.stereotype.Component;

/**
 * In-process queue between the API and {@link CommitAuthorizationService}. Not Kafka.
 * A caller may publish the same command more than once; the commit path treats that as
 * at-least-once delivery. The queue is not durable across a process crash.
 */
@Component
public class IngressQueue {

    private final BlockingQueue<PaymentEventCommand> commands = new LinkedBlockingQueue<>();

    public void publish(PaymentEventCommand command) {
        commands.add(command);
    }

    public PaymentEventCommand take() throws InterruptedException {
        return commands.take();
    }

    public PaymentEventCommand poll() {
        return commands.poll();
    }
}
