package io.github.huatalk.parallelinscope.scope;

import io.github.huatalk.parallelinscope.cancel.CancellationToken;
import io.github.huatalk.parallelinscope.context.TaskGraphObservationScope;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Immutable resolved state for one multi-task scope — a {@code Par.map} batch or one task-group
 * member; never cached by a {@code Par} or {@code GlobalPar}.
 *
 * <p>Resolution is the only place where user options become executable values: requested
 * parallelism is capped by task count, an explicit timeout uses the earlier of its own and any
 * parent deadline, and an inherited timeout resolves to the enclosing deadline (rejected when there
 * is none). The cancellation token is always a new child token, so cancellation propagates downward
 * without making child failure cancel its parent.
 */
public final class MultiTaskContext {
    private final String batchId;
    private final String taskName;
    private final int taskCount;
    private final int effectiveParallelism;
    private final long deadlineNanos;
    private final CancellationToken cancellationToken;
    private final @Nullable MultiTaskContext parent;
    private final @Nullable TaskGraphObservationScope taskGraphObservationScope;
    private final @Nullable ExecutorIdentity executorIdentity;
    private final @Nullable String parLabel;
    private final TaskType taskType;
    private final boolean rejectEnqueue;

    private MultiTaskContext(
            String taskName,
            int taskCount,
            int effectiveParallelism,
            long deadlineNanos,
            CancellationToken cancellationToken,
            @Nullable MultiTaskContext parent,
            @Nullable TaskGraphObservationScope taskGraphObservationScope,
            @Nullable ExecutorIdentity executorIdentity,
            @Nullable String parLabel,
            TaskType taskType,
            boolean rejectEnqueue) {
        this.batchId = UUID.randomUUID().toString();
        this.taskName = taskName;
        this.taskCount = taskCount;
        this.effectiveParallelism = effectiveParallelism;
        this.deadlineNanos = deadlineNanos;
        this.cancellationToken = cancellationToken;
        this.parent = parent;
        this.taskGraphObservationScope = taskGraphObservationScope;
        this.executorIdentity = executorIdentity;
        this.parLabel = parLabel;
        this.taskType = taskType;
        this.rejectEnqueue = rejectEnqueue;
    }

    /**
     * Resolves public options without binding a concrete {@code Par}. This overload is intended for
     * compatibility and tests; normal execution uses the identity-aware overload below.
     */
    public static MultiTaskContext resolve(MultiTaskOptions options, int taskCount, @Nullable MultiTaskContext parent) {
        return resolve(options, taskCount, parent, null);
    }

    public static MultiTaskContext resolve(
            MultiTaskOptions options,
            int taskCount,
            @Nullable MultiTaskContext parent,
            @Nullable TaskGraphObservationScope taskGraphObservationScope) {
        return resolve(options, taskCount, parent, taskGraphObservationScope, null, null);
    }

    /**
     * Resolves a unit while recording its concrete {@code Par} and supplied executor identity. The
     * identity is diagnostic and graph state, not a submission target; actual submission is owned by
     * the corresponding internal executor runtime.
     */
    public static MultiTaskContext resolve(
            MultiTaskOptions options,
            int taskCount,
            @Nullable MultiTaskContext parent,
            @Nullable TaskGraphObservationScope taskGraphObservationScope,
            @Nullable ExecutorIdentity executorIdentity,
            @Nullable String parLabel) {
        Objects.requireNonNull(options, "options cannot be null");
        if (!options.timeout().isPresent() && parent == null) {
            throw new IllegalArgumentException("no enclosing deadline to inherit; call timeout(Duration)");
        }
        TaskGraphObservationScope effectiveObservation = taskGraphObservationScope != null
                ? taskGraphObservationScope
                : parent == null ? null : parent.taskGraphObservationScope;
        return resolve(
                options,
                taskCount,
                parent,
                parent == null ? null : parent.cancellationToken,
                parent == null ? Long.MAX_VALUE : parent.deadlineNanos,
                System.nanoTime(),
                effectiveObservation,
                executorIdentity,
                parLabel);
    }

    /**
     * Resolves a unit whose structural parent, cancellation parent, and deadline ceiling are
     * independent. This is used by task-group members, where group cancellation is not a graph
     * parent and the group deadline is not necessarily the structural parent's deadline.
     */
    static MultiTaskContext resolve(
            MultiTaskOptions options,
            int taskCount,
            @Nullable MultiTaskContext structuralParent,
            @Nullable CancellationToken cancellationParent,
            long deadlineCeilingNanos,
            long resolutionTimeNanos,
            @Nullable TaskGraphObservationScope taskGraphObservationScope,
            @Nullable ExecutorIdentity executorIdentity,
            @Nullable String parLabel) {
        Objects.requireNonNull(options, "options cannot be null");
        if (taskCount < 0) throw new IllegalArgumentException("taskCount must not be negative");
        int requested = options.parallelism();
        int effective = requested <= 0 ? taskCount : Math.min(requested, taskCount);
        long deadline = resolveDeadlineNanos(options.timeout(), deadlineCeilingNanos, resolutionTimeNanos);
        return new MultiTaskContext(
                options.name(),
                taskCount,
                effective,
                deadline,
                new CancellationToken(cancellationParent, deadline),
                structuralParent,
                taskGraphObservationScope,
                executorIdentity,
                parLabel,
                options.taskType(),
                options.rejectEnqueue());
    }

    /**
     * Resolves a deadline from an explicit timeout or an enclosing ceiling. An explicit timeout
     * expires at the earlier of its own deadline and the ceiling; an empty timeout inherits the
     * ceiling verbatim. Overflow saturates to {@link Long#MAX_VALUE}.
     */
    static long resolveDeadlineNanos(Optional<Duration> timeout, long ceilingNanos, long nowNanos) {
        if (!timeout.isPresent()) {
            return ceilingNanos;
        }
        long timeoutNanos;
        try {
            timeoutNanos = timeout.get().toNanos();
        } catch (ArithmeticException overflow) {
            timeoutNanos = Long.MAX_VALUE;
        }
        long requestedDeadline =
                timeoutNanos > Long.MAX_VALUE - nowNanos ? Long.MAX_VALUE : nowNanos + timeoutNanos;
        return Math.min(requestedDeadline, ceilingNanos);
    }

    public String taskName() {
        return taskName;
    }

    /** Stable identity for this one batch instance; never use taskName as graph identity. */
    public String batchId() {
        return batchId;
    }

    public int taskCount() {
        return taskCount;
    }

    public int effectiveParallelism() {
        return effectiveParallelism;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    /** Returns a non-negative remaining timeout derived from the monotonic clock. */
    public Duration remaining() {
        long nanos = Math.max(0L, deadlineNanos - System.nanoTime());
        return Duration.ofNanos(nanos);
    }

    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    public MultiTaskContext parent() {
        return parent;
    }

    public TaskGraphObservationScope taskGraphObservationScope() {
        return taskGraphObservationScope;
    }

    public ExecutorIdentity executorIdentity() {
        return executorIdentity;
    }

    public String parLabel() {
        return parLabel;
    }

    public TaskType taskType() {
        return taskType;
    }

    public boolean rejectEnqueue() {
        return rejectEnqueue;
    }
}
