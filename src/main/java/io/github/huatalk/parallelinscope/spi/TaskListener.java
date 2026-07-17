package io.github.huatalk.parallelinscope.spi;

import javax.annotation.Nullable;
import java.time.Duration;

/**
 * SPI: Task lifecycle listener for metrics collection and monitoring.
 * <p>
 * Implementations can record task execution times, queue wait times, etc.
 * Register via {@link io.github.huatalk.parallelinscope.scope.ParConfig.Builder#taskListener(TaskListener)}.
 * <p>
 * Timing methods return {@link Duration}. Raw nanos timestamps are available via getters.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
@FunctionalInterface
public interface TaskListener {

    /**
     * Called when a task completes execution (both success and failure).
     *
     * @param event task execution event containing timing and metadata
     */
    void onTaskComplete(TaskEvent event);

    /**
     * Task execution event, contains all timing and metadata information
     */
    class TaskEvent {
        private final String taskName;
        private final long submitTimeNanos;
        private final long startTimeNanos;
        private final long endTimeNanos;
        private final boolean enqueued;
        private final Throwable exception;

        public TaskEvent(String taskName, long submitTimeNanos, long startTimeNanos,
                         long endTimeNanos, boolean enqueued, @Nullable Throwable exception) {
            this.taskName = taskName;
            this.submitTimeNanos = submitTimeNanos;
            this.startTimeNanos = startTimeNanos;
            this.endTimeNanos = endTimeNanos;
            this.enqueued = enqueued;
            this.exception = exception;
        }

        public String getTaskName() { return taskName; }
        public long getSubmitTimeNanos() { return submitTimeNanos; }
        public long getStartTimeNanos() { return startTimeNanos; }
        public long getEndTimeNanos() { return endTimeNanos; }
        public boolean isEnqueued() { return enqueued; }
        @Nullable
        public Throwable getException() { return exception; }

        /** Execution duration */
        public Duration executionTime() { return Duration.ofNanos(endTimeNanos - startTimeNanos); }

        /** Queue wait duration */
        public Duration waitTime() { return Duration.ofNanos(startTimeNanos - submitTimeNanos); }

        /** Total duration from submit to completion */
        public Duration totalTime() { return Duration.ofNanos(endTimeNanos - submitTimeNanos); }

    }
}
