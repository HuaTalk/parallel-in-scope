package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.cancel.*;
import io.github.huatalk.parallelinscope.context.*;
import io.github.huatalk.parallelinscope.context.graph.*;
import io.github.huatalk.parallelinscope.internal.*;
import io.github.huatalk.parallelinscope.queue.*;
import io.github.huatalk.parallelinscope.scope.*;
import io.github.huatalk.parallelinscope.spi.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Tests for cooperative cancellation checkpoints.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class CheckpointsTest {

    @AfterEach
    public void cleanup() {
        Thread.interrupted();
        TaskScopeTl.remove();
    }

    @Test
    public void testCheckpoint_noCancellation() {
        ParOptions options = ParOptions.of("myTask").build();
        CancellationToken token = CancellationToken.create();
        TaskScopeTl.init(token, options);

        // Should not throw
        Checkpoints.checkpoint("myTask", true);
    }

    @Test
    public void testCheckpoint_leanCancellation() {
        ParOptions options = ParOptions.of("myTask").build();
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, options);

        assertThatThrownBy(() -> Checkpoints.checkpoint("myTask", true))
                .isInstanceOf(LeanCancellationException.class);
    }

    @Test
    public void testCheckpoint_fatCancellation() {
        ParOptions options = ParOptions.of("myTask").build();
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, options);

        assertThatThrownBy(() -> Checkpoints.checkpoint("myTask", false))
                .isInstanceOf(FatCancellationException.class);
    }

    @Test
    public void testCheckpoint_differentTaskName_noThrow() {
        ParOptions options = ParOptions.of("taskA").build();
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, options);

        // Different task name - should not throw
        Checkpoints.checkpoint("taskB", true);
    }

    @Test
    public void testPublicMethods_cancelBeforePerformingOperation() {
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, ParOptions.of("myTask").build());

        java.time.Duration zero = java.time.Duration.ZERO;
        java.util.concurrent.TimeUnit nanos = java.util.concurrent.TimeUnit.NANOSECONDS;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(0);
        java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        java.util.concurrent.locks.Condition condition = lock.newCondition();
        Thread completed = new Thread(() -> { });
        java.util.concurrent.CompletableFuture<String> future =
                java.util.concurrent.CompletableFuture.completedFuture("done");
        java.util.concurrent.BlockingQueue<String> queue =
                new java.util.concurrent.ArrayBlockingQueue<>(1);
        java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(4);
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        executor.shutdown();

        assertAll("all public checkpoint operations observe the current cancellation token",
                () -> assertCancelled(Checkpoints::rawCheckpoint),
                () -> assertCancelled(() -> Checkpoints.sleep(0)),
                () -> assertCancelled(() -> Checkpoints.checkAwait(latch)),
                () -> assertCancelled(() -> Checkpoints.checkAwait(latch, zero)),
                () -> assertCancelled(() -> Checkpoints.checkAwait(latch, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkAwait(condition, zero)),
                () -> assertCancelled(() -> Checkpoints.checkAwait(condition, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkJoin(completed)),
                () -> assertCancelled(() -> Checkpoints.checkJoin(completed, zero)),
                () -> assertCancelled(() -> Checkpoints.checkJoin(completed, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkGet(future)),
                () -> assertCancelled(() -> Checkpoints.checkGet(future, zero)),
                () -> assertCancelled(() -> Checkpoints.checkGet(future, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkTake(queue)),
                () -> assertCancelled(() -> Checkpoints.checkPut(queue, "item")),
                () -> assertCancelled(() -> Checkpoints.checkSleep(zero)),
                () -> assertCancelled(() -> Checkpoints.checkSleep(0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkTryAcquire(semaphore, zero)),
                () -> assertCancelled(() -> Checkpoints.checkTryAcquire(semaphore, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkTryAcquire(semaphore, 1, zero)),
                () -> assertCancelled(() -> Checkpoints.checkTryAcquire(semaphore, 1, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkTryLock(lock, zero)),
                () -> assertCancelled(() -> Checkpoints.checkTryLock(lock, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkAwaitTermination(executor)),
                () -> assertCancelled(() -> Checkpoints.checkAwaitTermination(executor, zero)),
                () -> assertCancelled(() -> Checkpoints.checkAwaitTermination(executor, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkRunnable(() -> { }, RuntimeException.class)),
                () -> assertCancelled(() -> Checkpoints.checkSupplier(() -> "done", RuntimeException.class)),
                () -> assertCancelled(() -> Checkpoints.propagateCancellation(
                        new RuntimeException("not a cancellation"))));
    }

    @Test
    public void testLeanException_noStackTrace() {
        LeanCancellationException ex = new LeanCancellationException("test");
        assertThat(ex.getStackTrace()).isEmpty();
    }

    @Test
    public void testFatException_hasStackTrace() {
        FatCancellationException ex = new FatCancellationException("test");
        assertThat(ex.getStackTrace()).isNotEmpty();
    }

    // ==================== rawCheckpoint / sleep / propagateCancellation tests ====================

    @Test
    public void testRawCheckpoint_interruptedThread_throwsLeanCancellationException() {
        Thread.currentThread().interrupt();
        assertThatThrownBy(Checkpoints::rawCheckpoint)
                .isInstanceOf(LeanCancellationException.class);
        // interrupt flag should have been consumed by Thread.interrupted()
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    public void testSleep_interrupted_throwsLeanCancellationException() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> caught = new java.util.concurrent.atomic.AtomicReference<>();

        Thread t = new Thread(() -> {
            try {
                started.countDown();
                Checkpoints.sleep(5000);
            } catch (Throwable ex) {
                caught.set(ex);
            }
        });
        t.start();
        started.await(1, java.util.concurrent.TimeUnit.SECONDS);
        Thread.sleep(50); // small delay to ensure sleep is entered
        t.interrupt();
        t.join(2000);

        assertThat(caught.get()).isInstanceOf(LeanCancellationException.class);
    }

    @Test
    public void testCheckSupplier_returnsValue() {
        String result = Checkpoints.checkSupplier(() -> "VALUE", IllegalStateException.class);

        assertThat(result).isEqualTo("VALUE");
    }

    @Test
    public void testCheckSupplier_runtimeExceptionTriggersFatCancellation() {
        IllegalStateException failure = new IllegalStateException("failure");

        assertThatThrownBy(() -> Checkpoints.checkSupplier(() -> {
            throw failure;
        }, IllegalStateException.class))
                .isInstanceOf(FatCancellationException.class)
                .hasCause(failure);
    }

    @Test
    public void testCheckRunnable_runtimeExceptionTriggersFatCancellation() {
        assertThatThrownBy(() -> Checkpoints.checkRunnable(() -> {
            throw new IllegalStateException("failure");
        }, IllegalStateException.class)).isInstanceOf(FatCancellationException.class);
    }

    @Test
    public void testCheckSupplier_nonMatchingRuntimeExceptionIsPropagated() {
        IllegalArgumentException failure = new IllegalArgumentException("failure");

        assertThatThrownBy(() -> Checkpoints.checkSupplier(() -> {
            throw failure;
        }, IllegalStateException.class)).isSameAs(failure);
    }

    @Test
    public void testCheckRunnable_supertypeMatchesSubclass() {
        assertThatThrownBy(() -> Checkpoints.checkRunnable(() -> {
            throw new IllegalStateException("failure");
                }, RuntimeException.class)).isInstanceOf(FatCancellationException.class);
    }

    @Test
    public void testCheckRunnable_errorTypeTriggersFatCancellation() {
        AssertionError failure = new AssertionError("failure");

        assertThatThrownBy(() -> Checkpoints.checkRunnable(() -> {
            throw failure;
        }, AssertionError.class))
                .isInstanceOf(FatCancellationException.class)
                .hasCause(failure);
    }

    @Test
    public void testCheckRunnable_nonMatchingErrorIsPropagated() {
        AssertionError failure = new AssertionError("failure");

        assertThatThrownBy(() -> Checkpoints.checkRunnable(() -> {
            throw failure;
        }, OutOfMemoryError.class))
                .isSameAs(failure);
    }

    @Test
    public void testCheckSupplier_errorTypeTriggersFatCancellation() {
        AssertionError failure = new AssertionError("failure");

        assertThatThrownBy(() -> Checkpoints.checkSupplier(() -> {
            throw failure;
        }, AssertionError.class))
                .isInstanceOf(FatCancellationException.class)
                .hasCause(failure);
    }

    @Test
    public void testCheckGet_interruptedTriggersLeanCancellationAndRestoresFlag() {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> Checkpoints.checkGet(future))
                .isInstanceOf(LeanCancellationException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    public void testCheckMethods_coverUninterruptiblesObjectTypes() throws Exception {
        java.time.Duration zero = java.time.Duration.ZERO;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(0);
        Checkpoints.checkAwait(latch);
        assertThat(Checkpoints.checkAwait(latch, zero)).isTrue();
        assertThat(Checkpoints.checkAwait(latch, 0, java.util.concurrent.TimeUnit.NANOSECONDS)).isTrue();

        java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        lock.lock();
        try {
            assertThat(Checkpoints.checkAwait(lock.newCondition(), zero)).isFalse();
            assertThat(Checkpoints.checkAwait(
                    lock.newCondition(), 0, java.util.concurrent.TimeUnit.NANOSECONDS)).isFalse();
        } finally {
            lock.unlock();
        }

        Thread completed = new Thread(() -> { });
        completed.start();
        completed.join();
        Checkpoints.checkJoin(completed);
        Checkpoints.checkJoin(completed, zero);
        Checkpoints.checkJoin(completed, 0, java.util.concurrent.TimeUnit.NANOSECONDS);

        java.util.concurrent.CompletableFuture<String> future =
                java.util.concurrent.CompletableFuture.completedFuture("done");
        assertThat(Checkpoints.checkGet(future)).isEqualTo("done");
        assertThat(Checkpoints.checkGet(future, zero)).isEqualTo("done");
        assertThat(Checkpoints.checkGet(future, 0, java.util.concurrent.TimeUnit.NANOSECONDS))
                .isEqualTo("done");

        java.util.concurrent.BlockingQueue<String> queue =
                new java.util.concurrent.ArrayBlockingQueue<>(1);
        Checkpoints.checkPut(queue, "item");
        assertThat(Checkpoints.checkTake(queue)).isEqualTo("item");
        Checkpoints.checkSleep(zero);
        Checkpoints.checkSleep(0, java.util.concurrent.TimeUnit.NANOSECONDS);

        java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(4);
        assertThat(Checkpoints.checkTryAcquire(semaphore, zero)).isTrue();
        assertThat(Checkpoints.checkTryAcquire(
                semaphore, 0, java.util.concurrent.TimeUnit.NANOSECONDS)).isTrue();
        assertThat(Checkpoints.checkTryAcquire(semaphore, 1, zero)).isTrue();
        assertThat(Checkpoints.checkTryAcquire(
                semaphore, 1, 0, java.util.concurrent.TimeUnit.NANOSECONDS)).isTrue();

        assertThat(Checkpoints.checkTryLock(lock, zero)).isTrue();
        lock.unlock();
        assertThat(Checkpoints.checkTryLock(
                lock, 0, java.util.concurrent.TimeUnit.NANOSECONDS)).isTrue();
        lock.unlock();

        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        executor.shutdown();
        Checkpoints.checkAwaitTermination(executor);
        assertThat(Checkpoints.checkAwaitTermination(executor, zero)).isTrue();
        assertThat(Checkpoints.checkAwaitTermination(
                executor, 0, java.util.concurrent.TimeUnit.NANOSECONDS)).isTrue();
    }

    @Test
    public void testPropagateCancellation_rethrowsFatCancellationException() {
        FatCancellationException ex = new FatCancellationException("test");
        assertThatThrownBy(() -> Checkpoints.propagateCancellation(ex))
                .isInstanceOf(FatCancellationException.class);
    }

    @Test
    public void testPropagateCancellation_rethrowsLeanCancellationException() {
        LeanCancellationException ex = new LeanCancellationException("test");
        assertThatThrownBy(() -> Checkpoints.propagateCancellation(ex))
                .isInstanceOf(LeanCancellationException.class);
    }

    @Test
    public void testPropagateCancellation_doesNothingForOtherExceptions() {
        RuntimeException ex = new RuntimeException("not a cancellation");
        // Should not throw
        Checkpoints.propagateCancellation(ex);
    }

    private static void assertCancelled(Executable executable) {
        assertThatThrownBy(executable::execute)
                .isInstanceOf(LeanCancellationException.class)
                .hasMessage("Cancel during running");
    }
}
