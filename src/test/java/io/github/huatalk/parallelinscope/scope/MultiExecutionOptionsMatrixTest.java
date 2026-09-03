package io.github.huatalk.parallelinscope.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Getter/builder matrix over {@link MultiExecutionOptions}: chained builder calls, default values,
 * per-field round trips, and timeout validation.
 */
class MultiExecutionOptionsMatrixTest {

    @Test
    void defaultsComeFromTheBuilderFieldInitializers() {
        MultiExecutionOptions options =
                MultiExecutionOptions.of("n").timeout(Duration.ofSeconds(30)).build();
        assertThat(options.name()).isEqualTo("n");
        assertThat(options.parallelism()).isEqualTo(-1);
        assertThat(options.taskType()).isEqualTo(TaskType.CPU_BOUND);
        assertThat(options.rejectEnqueue()).isTrue();
    }

    @Test
    void chainedBuilderCallsKeepReturningTheBuilder() {
        MultiExecutionOptions.Builder builder = MultiExecutionOptions.builder();
        MultiExecutionOptions options = builder.name("chain")
                .parallelism(3)
                .timeout(Duration.ofSeconds(2))
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();

        assertThat(options.name()).isEqualTo("chain");
        assertThat(options.parallelism()).isEqualTo(3);
        assertThat(options.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(options.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(options.rejectEnqueue()).isFalse();

        // The built snapshot is immune to later builder mutations.
        builder.taskType(TaskType.CPU_BOUND);
        assertThat(options.taskType()).isEqualTo(TaskType.IO_BOUND);
    }

    @Test
    void missingOrExplicitNullTimeoutIsRejectedAsRequired() {
        assertThatThrownBy(() -> MultiExecutionOptions.of("t").build()).isInstanceOf(NullPointerException.class);
        MultiExecutionOptions.Builder builder = MultiExecutionOptions.of("t");
        builder.timeout(null);
        assertThatThrownBy(builder::build).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> MultiExecutionOptions.of("t").timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MultiExecutionOptions.of("t").timeout(Duration.ofMillis(-5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bothRejectEnqueuePolaritiesRoundTrip() {
        assertThat(MultiExecutionOptions.of("a")
                        .rejectEnqueue(true)
                        .timeout(Duration.ofSeconds(30))
                        .build()
                        .rejectEnqueue())
                .isTrue();
        assertThat(MultiExecutionOptions.of("b")
                        .rejectEnqueue(false)
                        .timeout(Duration.ofSeconds(30))
                        .build()
                        .rejectEnqueue())
                .isFalse();
    }

    @Test
    void nameIsPreservedVerbatim() {
        String longName = "order-pipeline-stage-7";
        assertThat(MultiExecutionOptions.of(longName)
                        .timeout(Duration.ofSeconds(30))
                        .build()
                        .name())
                .isEqualTo(longName);
    }
}
