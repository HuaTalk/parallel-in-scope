package io.github.huatalk.parallelinscope.cancel;

import com.google.common.base.Throwables;
import io.github.huatalk.parallelinscope.context.TaskScopeTl;
import io.github.huatalk.parallelinscope.scope.ParOptions;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/**
 * Cooperative cancellation checkpoint utility.
 * <p>
 * Since Java's {@link Thread#interrupt()} only affects blocking operations,
 * this class provides explicit checkpoint methods that CPU-bound code can call
 * to check whether it should abort early.
 * <p>
 * Cancellation exception selection is based on whether the observed exception
 * is the cancellation source or only a propagated cancellation signal:
 * <ul>
 *   <li>An {@link InterruptedException} is normally caused by an upstream timeout,
 *       fail-fast failure, parent cancellation, or explicit cancellation. Because
 *       the root cause is recorded upstream, interruption-aware methods throw
 *       {@link LeanCancellationException} without another redundant stack trace.</li>
 *   <li>An exception matched by {@link #checkRunnable(Runnable, Class)} or
 *       {@link #checkSupplier(Supplier, Class)} is itself the cancellation source.
 *       These methods throw {@link FatCancellationException} and retain the matched
 *       exception as the cause so the cancellation origin remains diagnosable.</li>
 *   <li>A non-matching runtime exception or {@link Error} is propagated unchanged;
 *       an unexpected checked throwable is surfaced as an {@link AssertionError}.</li>
 * </ul>
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class Checkpoints {

    private Checkpoints() {
    }

    /**
     * Standard checkpoint: checks CancellationToken state from TaskScopeTl.
     * Throws cancellation exception if the task has been canceled.
     *
     * @param taskName the task name for validation
     * @param lean     true to throw LeanCancellationException (no stack trace),
     *                 false to throw FatCancellationException (full stack trace)
     */
    public static void checkpoint(String taskName, boolean lean) {
        ParOptions options = TaskScopeTl.getParallelOptions();
        if (taskName == null || options == null
                || !taskName.equals(options.getTaskName())) {
            return;
        }
        CancellationToken cancelToken = TaskScopeTl.getCancellationToken();
        if (cancelToken != null) {
            if (cancelToken.getState().shouldInterruptCurrentThread()) {
                throw lean
                        ? new LeanCancellationException("Cancel during running")
                        : new FatCancellationException("Cancel during running");
            }
        }
    }

    /**
     * Raw checkpoint: only checks thread interrupt flag.
     * For scenarios not using CancellationToken.
     */
    public static void rawCheckpoint() {
        if (Thread.interrupted()) {
            throw cancellation("Cancel during running by interruption");
        }
    }

    /**
     * Cancellation-aware sleep. Converts {@link InterruptedException} into
     * a {@link LeanCancellationException} so that task cancellation via
     * thread interrupt is treated uniformly as a cooperative cancellation.
     *
     * @param millis sleep duration in milliseconds
     */
    public static void sleep(long millis) {
        checkSleep(millis, TimeUnit.MILLISECONDS);
    }

    /** Checks an interruptible {@link CountDownLatch#await()} operation. */
    public static void checkAwait(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw interrupted("Cancel during latch await by interruption");
        }
    }

    /** Checks an interruptible timed {@link CountDownLatch#await(long, TimeUnit)} operation. */
    public static boolean checkAwait(CountDownLatch latch, Duration timeout) {
        return checkAwait(latch, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Checks an interruptible timed {@link CountDownLatch#await(long, TimeUnit)} operation. */
    public static boolean checkAwait(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during latch await by interruption");
        }
    }

    /** Checks an interruptible timed {@link Condition#await(long, TimeUnit)} operation. */
    public static boolean checkAwait(Condition condition, Duration timeout) {
        return checkAwait(condition, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Checks an interruptible timed {@link Condition#await(long, TimeUnit)} operation. */
    public static boolean checkAwait(Condition condition, long timeout, TimeUnit unit) {
        try {
            return condition.await(timeout, unit);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during condition await by interruption");
        }
    }

    /** Checks an interruptible {@link Thread#join()} operation. */
    public static void checkJoin(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw interrupted("Cancel during thread join by interruption");
        }
    }

    /** Checks an interruptible timed {@link Thread#join(long, int)} operation. */
    public static void checkJoin(Thread thread, Duration timeout) {
        checkJoin(thread, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Checks an interruptible timed join operation. */
    public static void checkJoin(Thread thread, long timeout, TimeUnit unit) {
        try {
            unit.timedJoin(thread, timeout);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during thread join by interruption");
        }
    }

    /** Checks an interruptible {@link Future#get()} operation. */
    public static <V> V checkGet(Future<V> future) throws ExecutionException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            throw interrupted("Cancel during future get by interruption");
        }
    }

    /** Checks an interruptible timed {@link Future#get(long, TimeUnit)} operation. */
    public static <V> V checkGet(Future<V> future, Duration timeout)
            throws ExecutionException, TimeoutException {
        return checkGet(future, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Checks an interruptible timed {@link Future#get(long, TimeUnit)} operation. */
    public static <V> V checkGet(Future<V> future, long timeout, TimeUnit unit)
            throws ExecutionException, TimeoutException {
        try {
            return future.get(timeout, unit);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during future get by interruption");
        }
    }

    /** Checks an interruptible {@link BlockingQueue#take()} operation. */
    public static <E> E checkTake(BlockingQueue<E> queue) {
        try {
            return queue.take();
        } catch (InterruptedException e) {
            throw interrupted("Cancel during queue take by interruption");
        }
    }

    /** Checks an interruptible {@link BlockingQueue#put(Object)} operation. */
    public static <E> void checkPut(BlockingQueue<E> queue, E element) {
        try {
            queue.put(element);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during queue put by interruption");
        }
    }

    /** Checks an interruptible sleep operation. */
    public static void checkSleep(Duration duration) {
        checkSleep(duration.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Checks an interruptible sleep operation. */
    public static void checkSleep(long duration, TimeUnit unit) {
        try {
            unit.sleep(duration);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during sleep by interruption");
        }
    }

    /** Checks an interruptible semaphore acquisition for one permit. */
    public static boolean checkTryAcquire(Semaphore semaphore, Duration timeout) {
        return checkTryAcquire(semaphore, 1, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Checks an interruptible semaphore acquisition for one permit. */
    public static boolean checkTryAcquire(Semaphore semaphore, long timeout, TimeUnit unit) {
        return checkTryAcquire(semaphore, 1, timeout, unit);
    }

    /** Checks an interruptible semaphore acquisition. */
    public static boolean checkTryAcquire(Semaphore semaphore, int permits, Duration timeout) {
        return checkTryAcquire(semaphore, permits, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Checks an interruptible semaphore acquisition. */
    public static boolean checkTryAcquire(
            Semaphore semaphore, int permits, long timeout, TimeUnit unit) {
        try {
            return semaphore.tryAcquire(permits, timeout, unit);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during semaphore acquisition by interruption");
        }
    }

    /** Checks an interruptible timed lock acquisition. */
    public static boolean checkTryLock(Lock lock, Duration timeout) {
        return checkTryLock(lock, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Checks an interruptible timed lock acquisition. */
    public static boolean checkTryLock(Lock lock, long timeout, TimeUnit unit) {
        try {
            return lock.tryLock(timeout, unit);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during lock acquisition by interruption");
        }
    }

    /** Checks an interruptible executor termination wait without a practical timeout. */
    public static void checkAwaitTermination(ExecutorService executor) {
        checkAwaitTermination(executor, Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    /** Checks an interruptible timed executor termination wait. */
    public static boolean checkAwaitTermination(ExecutorService executor, Duration timeout) {
        return checkAwaitTermination(executor, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Checks an interruptible timed executor termination wait. */
    public static boolean checkAwaitTermination(
            ExecutorService executor, long timeout, TimeUnit unit) {
        try {
            return executor.awaitTermination(timeout, unit);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during executor termination wait by interruption");
        }
    }

    /**
     * Runs a no-argument action and converts an exception of the declared type
     * into fat cancellation. Other unchecked throwables are propagated unchanged;
     * an unexpected checked throwable becomes an {@link AssertionError}.
     */
    public static <X extends Throwable> void checkRunnable(
            Runnable action, Class<X> declaredType) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(declaredType, "declaredType");
        try {
            action.run();
        } catch (Throwable t) {
            throwIfCancellationTrigger(t, declaredType, "checked action");
            rethrowUnchecked(t);
        }
    }

    /**
     * Gets a value and converts an exception of the declared type into fat
     * cancellation. Other unchecked throwables are propagated unchanged;
     * an unexpected checked throwable becomes an {@link AssertionError}.
     */
    public static <T, X extends Throwable> T checkSupplier(
            Supplier<? extends T> supplier, Class<X> declaredType) {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(declaredType, "declaredType");
        try {
            return supplier.get();
        } catch (Throwable t) {
            throwIfCancellationTrigger(t, declaredType, "checked supplier");
            return Checkpoints.<T>rethrowUnchecked(t);
        }
    }

    /**
     * Re-throws if the throwable is a cancellation exception.
     *
     * @param ex the exception to check
     */
    public static void propagateCancellation(Throwable ex) {
        Throwables.throwIfInstanceOf(ex, FatCancellationException.class);
        Throwables.throwIfInstanceOf(ex, LeanCancellationException.class);
    }

    private static LeanCancellationException interrupted(String message) {
        Thread.currentThread().interrupt();
        return cancellation(message);
    }

    private static LeanCancellationException cancellation(String message) {
        return new LeanCancellationException(message);
    }

    private static <X extends Throwable> void throwIfCancellationTrigger(
            Throwable throwable, Class<X> declaredType, String operation) {
        if (declaredType.isInstance(throwable)) {
            X matched = declaredType.cast(throwable);
            FatCancellationException cancellation = new FatCancellationException(
                    "Cancel during " + operation + ": " + matched.getClass().getSimpleName());
            cancellation.initCause(matched);
            throw cancellation;
        }
    }

    /** Preserves unchecked failures while making an impossible checked failure explicit. */
    private static <T> T rethrowUnchecked(Throwable throwable) {
        if (throwable instanceof RuntimeException) {
            throw (RuntimeException) throwable;
        }
        if (throwable instanceof Error) {
            throw (Error) throwable;
        }
        throw new AssertionError("Runnable/Supplier threw a checked Throwable", throwable);
    }
}
