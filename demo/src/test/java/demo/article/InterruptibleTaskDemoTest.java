package demo.article;

import demo.basic.InterruptibleTaskDemo;
import io.github.huatalk.parallelinscope.cancel.CancellationChecker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InterruptibleTaskDemoTest {

    @Test
    void countTriggerChecksOnlyAtTheConfiguredBoundary() {
        CancellationChecker checker = CancellationChecker.every(3);

        assertThat(checker.shouldCheck()).isFalse();
        assertThat(checker.shouldCheck()).isFalse();
        assertThat(checker.shouldCheck()).isTrue();
    }

    @Test
    void combinedTriggerRequiresBothCountAndTimeBoundaries() {
        CancellationChecker checker = CancellationChecker.whenBoth(3, Duration.ofHours(1));

        assertThat(checker.shouldCheck()).isFalse();
        assertThat(checker.shouldCheck()).isFalse();
        assertThat(checker.shouldCheck()).isFalse();
    }

    @Test
    void boundCheckRunsOnlyWhenDue() {
        AtomicInteger checks = new AtomicInteger();
        CancellationChecker checker = CancellationChecker.every(2, checks::incrementAndGet);

        assertThat(checker.check()).isFalse();
        assertThat(checker.check()).isTrue();
        assertThat(checks).hasValue(1);
    }

    @Test
    void checkIfDueAcceptsAOneOffCheck() {
        AtomicInteger checks = new AtomicInteger();
        CancellationChecker checker = CancellationChecker.every(2);

        assertThat(checker.checkIfDue(checks::incrementAndGet)).isFalse();
        assertThat(checker.checkIfDue(checks::incrementAndGet)).isTrue();
        assertThat(checks).hasValue(1);
    }

    @Test
    void cpuLoopStopsAtTheNextCheckpointAfterInterrupt() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                InterruptibleTaskDemo.runTask(Integer.MAX_VALUE, 1, 1);
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        worker.start();
        Thread.sleep(10);
        worker.interrupt();
        worker.join(1_000);

        assertThat(failure.get()).isInstanceOf(RuntimeException.class);
    }
}
