package io.github.huatalk.parallelinscope.internal;

import com.google.common.util.concurrent.AbstractFuture;
import io.github.huatalk.parallelinscope.spi.ExecutionPhase;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A listenable future and runnable that publishes hints about its execution phase.
 *
 * <p>This is the shared single-task future for the whole library: {@code Par.map} submits it via
 * {@link ListenableCompletionService}, and {@code ParallelTaskGroup} builds it deferred and submits
 * it after its frozen build boundary. Both call sites share this one phase state machine; it must
 * not be re-implemented per caller.
 *
 * @param <V> result type
 */
public final class ExecutionPhaseHintFuture<V> extends AbstractFuture<V> implements RunnableFuture<V> {

    private static final Logger LOGGER = Logger.getLogger(ExecutionPhaseHintFuture.class.getName());
    private static final Consumer<ExecutionPhase> NOOP = phase -> {};

    /**
     * Tracks whether the worker or cancellation claimed the task first. The resulting phase is a hint
     * for consumers such as queue maintenance; it is not an exact queue-membership probe. This is
     * separate from the future state maintained by {@link AbstractFuture}.
     *
     * <pre>
     * Phase                       Meaning
     * --------------------------  -------------------------------------------------------------
     * SUBMITTED                   No worker has claimed the runnable yet.
     * RUNNING                     A worker has claimed the runnable.
     * CANCELLED_BEFORE_RUN        Cancellation won before run() claimed the runnable.
     * CANCEL_REQUESTED_RUNNING    Cancellation followed a run() claim.
     * TERMINAL                    run() has returned.
     *
     * State transitions:
     *
     *                          cancellation wins
     * SUBMITTED ----------------------------------------------> CANCELLED_BEFORE_RUN
     *     |
     *     | worker run() wins
     *     v
     *  RUNNING ---- cancellation succeeds ----> CANCEL_REQUESTED_RUNNING
     *     |                                              |
     *     +---------------- run() returns ---------------+
     *                            |
     *                            v
     *                         TERMINAL
     * </pre>
     */
    private final Callable<V> callable;

    private final AtomicReference<ExecutionPhase> phase = new AtomicReference<>(ExecutionPhase.SUBMITTED);

    private volatile Consumer<? super ExecutionPhase> phaseObserver;
    private volatile Thread runner;

    /** Creates a future with a phase observer. */
    public static <V> ExecutionPhaseHintFuture<V> create(
            Callable<V> callable, Consumer<? super ExecutionPhase> phaseObserver) {
        return new ExecutionPhaseHintFuture<>(callable, phaseObserver);
    }

    /** Creates a future with a phase observer for a runnable and fixed result. */
    public static <V> ExecutionPhaseHintFuture<V> create(
            Runnable runnable, V result, Consumer<? super ExecutionPhase> phaseObserver) {
        Objects.requireNonNull(runnable, "runnable cannot be null");
        return new ExecutionPhaseHintFuture<>(
                () -> {
                    runnable.run();
                    return result;
                },
                phaseObserver);
    }

    /**
     * Creates a future whose executor invocation is deferred until {@link #submitPrepared(Executor,
     * boolean)} is called. This is the "frozen build boundary" form used by {@code
     * ParallelTaskGroup}: the future exists and can be bound to handles before any task is allowed
     * to run.
     */
    public static <V> ExecutionPhaseHintFuture<V> createDeferred(
            Callable<V> callable, Consumer<? super ExecutionPhase> phaseObserver) {
        return create(callable, phaseObserver);
    }

    /** Wraps Guava's future semantics with task-local execution-phase hints. */
    private ExecutionPhaseHintFuture(Callable<V> callable, Consumer<? super ExecutionPhase> phaseObserver) {
        this.callable = Objects.requireNonNull(callable, "callable cannot be null");
        this.phaseObserver = Objects.requireNonNull(phaseObserver);
    }

    /**
     * Submits this deferred future to {@code executor} exactly once. A {@code CPU_BOUND} task that
     * the executor rejects runs inline; any other rejection, or a submission-time runtime failure,
     * fails the future with a {@link SubmissionException} without running user code.
     *
     * @param executor target executor
     * @param cpuBound whether the task may fall back to inline execution on rejection
     */
    public void submitPrepared(Executor executor, boolean cpuBound) {
        try {
            executor.execute(this);
        } catch (RejectedExecutionException rejected) {
            if (cpuBound) {
                run();
            } else {
                reject(rejected);
            }
        } catch (RuntimeException failure) {
            reject(failure);
        }
    }

    /**
     * Fails the future with a {@link SubmissionException} when it has not started running. A future
     * that already claimed {@code RUNNING} or is otherwise terminal is left untouched.
     */
    private void reject(Throwable failure) {
        if (phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.TERMINAL)) {
            setException(new SubmissionException(failure));
            notifyPhase(ExecutionPhase.TERMINAL);
            phaseObserver = NOOP;
        }
    }

    /** Runs the delegate only after claiming the transition out of the submitted state. */
    @Override
    public void run() {
        if (!phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.RUNNING)) {
            return;
        }
        runner = Thread.currentThread();
        notifyPhase(ExecutionPhase.RUNNING);
        try {
            if (!isCancelled()) {
                set(callable.call());
            }
        } catch (Throwable failure) {
            setException(failure);
        } finally {
            runner = null;
            phase.set(ExecutionPhase.TERMINAL);
            notifyPhase(ExecutionPhase.TERMINAL);
            phaseObserver = NOOP;
        }
    }

    /** Classifies successful cancellation using the same state raced by {@link #run()}. */
    @Override
    protected void interruptTask() {
        Thread executing = runner;
        if (executing != null) {
            executing.interrupt();
        }
    }

    @Override
    protected void afterDone() {
        if (!isCancelled()) {
            return;
        }
        while (true) {
            ExecutionPhase current = phase.get();
            if (current == ExecutionPhase.SUBMITTED) {
                if (phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.CANCELLED_BEFORE_RUN)) {
                    notifyPhase(ExecutionPhase.CANCELLED_BEFORE_RUN);
                    phaseObserver = NOOP;
                    return;
                }
            } else if (current == ExecutionPhase.RUNNING) {
                if (phase.compareAndSet(ExecutionPhase.RUNNING, ExecutionPhase.CANCEL_REQUESTED_RUNNING)) {
                    notifyPhase(ExecutionPhase.CANCEL_REQUESTED_RUNNING);
                    return;
                }
            } else {
                return;
            }
        }
    }

    private void notifyPhase(ExecutionPhase phase) {
        try {
            phaseObserver.accept(phase);
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "Execution phase observer failed", e);
        }
    }
}
