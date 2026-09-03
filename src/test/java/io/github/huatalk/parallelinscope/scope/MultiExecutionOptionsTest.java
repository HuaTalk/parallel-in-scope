package io.github.huatalk.parallelinscope.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MultiExecutionOptionsTest {
    @Test
    void remainsAnImmutablePerCallInput() {
        MultiExecutionOptions options = MultiExecutionOptions.of("load")
                .parallelism(3)
                .timeout(Duration.ofSeconds(2))
                .taskType(TaskType.IO_BOUND)
                .build();

        assertThat(options.name()).isEqualTo("load");
        assertThat(options.parallelism()).isEqualTo(3);
        assertThat(options.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(options.taskType()).isEqualTo(TaskType.IO_BOUND);
    }

    @Test
    void rejectsNonPositiveExplicitTimeouts() {
        assertThatThrownBy(() -> MultiExecutionOptions.of("load").timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MultiExecutionOptions.of("load").timeout(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsAllExecutionHints() {
        MultiExecutionOptions options = MultiExecutionOptions.of("write")
                .parallelism(7)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .timeout(Duration.ofSeconds(30))
                .build();

        assertThat(options.parallelism()).isEqualTo(7);
        assertThat(options.rejectEnqueue()).isFalse();
        assertThat(options.timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void timeoutIsMandatory() {
        assertThatThrownBy(() -> MultiExecutionOptions.of("read").build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void resolvesExplicitTimeoutAndParallelismIntoBatchContext() {
        BatchExecutionContext context = BatchExecutionContext.resolve(
                MultiExecutionOptions.of("write")
                        .parallelism(8)
                        .timeout(Duration.ofSeconds(3))
                        .taskType(TaskType.IO_BOUND)
                        .rejectEnqueue(false)
                        .build(),
                2,
                null);

        assertThat(context.taskName()).isEqualTo("write");
        assertThat(context.effectiveParallelism()).isEqualTo(2);
        assertThat(context.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(context.rejectEnqueue()).isFalse();
    }

    @Test
    void resolvesExplicitTimeoutIntoBatchContext() {
        BatchExecutionContext context = BatchExecutionContext.resolve(
                MultiExecutionOptions.of("read")
                        .parallelism(-1)
                        .timeout(Duration.ofSeconds(3))
                        .build(),
                4,
                null);

        assertThat(context.effectiveParallelism()).isEqualTo(4);
        assertThat(context.remaining().toMillis()).isLessThanOrEqualTo(3_000);
    }
}
