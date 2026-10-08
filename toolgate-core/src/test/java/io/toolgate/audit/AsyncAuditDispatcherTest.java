package io.toolgate.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.Mode;
import io.toolgate.spi.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AsyncAuditDispatcherTest {

    private final List<AuditEvent> received = new CopyOnWriteArrayList<>();

    @Test
    void deliversInOrderAndFlushesOnClose() {
        var dispatcher = new AsyncAuditDispatcher(received::add, 100);
        for (int index = 0; index < 50; index++) {
            int agents = index;
            dispatcher.submit(() -> event(agents));
        }

        dispatcher.close();

        assertThat(received).extracting(event -> ((AuditEvent.PolicyLoaded) event).agents())
                .containsExactlyElementsOf(IntStream.range(0, 50).boxed().toList());
        assertThat(dispatcher.dropped()).isZero();
    }

    @Test
    void countsEventsSubmittedAfterClose() {
        var dispatcher = new AsyncAuditDispatcher(received::add, 100);
        dispatcher.close();

        dispatcher.submit(() -> event(1));

        assertThat(dispatcher.dropped()).isEqualTo(1);
        assertThat(received).isEmpty();
    }

    /** A slow sink never blocks a decision: the overflow is counted instead. */
    @Test
    void countsEventsThatDoNotFitInTheQueue() throws InterruptedException {
        var sinkEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var dispatcher = new AsyncAuditDispatcher(event -> {
            sinkEntered.countDown();
            await(release);
            received.add(event);
        }, 10);
        dispatcher.submit(() -> event(0));
        assertThat(sinkEntered.await(5, TimeUnit.SECONDS)).isTrue();

        for (int index = 1; index <= 13; index++) {
            int agents = index;
            dispatcher.submit(() -> event(agents));
        }
        release.countDown();
        dispatcher.close();

        assertThat(dispatcher.dropped()).isEqualTo(3);
        assertThat(received).hasSize(11);
    }

    @Test
    void countsSinkAndBuilderFailuresAndKeepsGoing() {
        var dispatcher = new AsyncAuditDispatcher(event -> {
            if (((AuditEvent.PolicyLoaded) event).agents() == 1) {
                throw new IllegalStateException("sink down");
            }
            received.add(event);
        }, 100);

        dispatcher.submit(() -> event(1));
        dispatcher.submit(() -> {
            throw new IllegalStateException("could not build");
        });
        dispatcher.submit(() -> event(2));
        dispatcher.close();

        assertThat(dispatcher.dropped()).isEqualTo(2);
        assertThat(received).hasSize(1);
    }

    private static AuditEvent event(int agents) {
        return new AuditEvent.PolicyLoaded(Instant.EPOCH, "sha256:x", Mode.ENFORCE, agents);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
