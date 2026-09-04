package io.github.huatalk.parallelinscope.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.google.common.util.concurrent.MoreExecutors;
import io.github.huatalk.parallelinscope.cancel.CancellationToken;
import io.github.huatalk.parallelinscope.context.SubmissionScope;
import io.github.huatalk.parallelinscope.internal.TaskExecutionContext;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ParallelTaskGroupTest {
    @Test
    void buildsAndSubmitsHeterogeneousMembersAtOneBoundary() throws Exception {
        ExecutorService first = Executors.newSingleThreadExecutor();
        ExecutorService second = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder()
                .register("first", first)
                .register("second", second)
                .build();
        try {
            AtomicInteger executions = new AtomicInteger();
            ParallelTaskGroup.Builder builder = global.taskGroupBuilder(
                    MultiTaskOptions.of("page").timeout(Duration.ofSeconds(30)).build());
            ParallelTaskGroup.TaskHandle<String> text = builder.addTask(
                    "text",
                    global.par("first"),
                    () -> "value-" + executions.incrementAndGet(),
                    MultiTaskOptions.of("text").timeout(Duration.ofSeconds(30)).build());
            ParallelTaskGroup.TaskHandle<Integer> number = builder.addTask(
                    "number",
                    global.par("second"),
                    () -> 40 + executions.incrementAndGet(),
                    MultiTaskOptions.of("number")
                            .timeout(Duration.ofSeconds(30))
                            .build());

            assertThatThrownBy(text::future).isInstanceOf(IllegalStateException.class);

            ParallelTaskGroup group = builder.buildAndSubmitAll();
            assertThat(text.future().get(2, TimeUnit.SECONDS)).startsWith("value-");
            assertThat(number.future().get(2, TimeUnit.SECONDS)).isBetween(41, 42);
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(executions).hasValue(2);
            assertThat(result.completionReason()).isEqualTo(TaskGroupCompletionReason.SUCCESS);
            assertThat(result.members().keySet()).containsExactly("text", "number");
            assertThat(group.members().keySet()).containsExactly("text", "number");
            assertThat(group.findMember("text")).contains(text.future());
            assertThatThrownBy(builder::buildAndSubmitAll).isInstanceOf(IllegalStateException.class);
        } finally {
            global.close();
            first.shutdownNow();
            second.shutdownNow();
        }
    }

    @Test
    void addTaskIsConfigurationOnlyAndTtlSnapshotIsTakenAtBuild() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        TransmittableThreadLocal<String> ttl = new TransmittableThreadLocal<>();
        try {
            AtomicInteger calls = new AtomicInteger();
            ttl.set("add");
            ParallelTaskGroup.Builder builder = global.taskGroupBuilder(
                    MultiTaskOptions.of("ttl").timeout(Duration.ofSeconds(30)).build());
            ParallelTaskGroup.TaskHandle<String> handle = builder.addTask(
                    "member",
                    global.par("worker"),
                    () -> {
                        calls.incrementAndGet();
                        return ttl.get();
                    },
                    MultiTaskOptions.of("member")
                            .timeout(Duration.ofSeconds(30))
                            .build());

            assertThat(calls).hasValue(0);
            ttl.set("build");
            builder.buildAndSubmitAll();

            assertThat(handle.future().get(2, TimeUnit.SECONDS)).isEqualTo("build");
            assertThat(calls).hasValue(1);
        } finally {
            ttl.remove();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void failureIsFailFastAndCancelsUnfinishedSibling() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        CountDownLatch running = new CountDownLatch(1);
        try {
            ParallelTaskGroup.Builder builder = global.taskGroupBuilder(MultiTaskOptions.of("fail-fast")
                    .timeout(Duration.ofSeconds(30))
                    .build());
            builder.addTask(
                    "slow",
                    global.par("worker"),
                    () -> {
                        running.countDown();
                        Thread.sleep(10_000);
                        return 1;
                    },
                    MultiTaskOptions.of("slow").timeout(Duration.ofSeconds(30)).build());
            builder.addTask(
                    "failure",
                    global.par("worker"),
                    () -> {
                        running.await(2, TimeUnit.SECONDS);
                        throw new IllegalStateException("boom");
                    },
                    MultiTaskOptions.of("failure")
                            .timeout(Duration.ofSeconds(30))
                            .build());

            TaskGroupResult result =
                    builder.buildAndSubmitAll().completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.completionReason()).isEqualTo(TaskGroupCompletionReason.FAILED);
            assertThat(result.failedMemberName()).isEqualTo("failure");
            assertThat(result.members().get("failure").completionReason()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.members().get("slow").completionReason()).isEqualTo(TaskOutcome.FAIL_FAST);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void groupAndMemberDeadlinesConvergeAsTimeout() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            ParallelTaskGroup.Builder groupDeadline = global.taskGroupBuilder(MultiTaskOptions.of("group-timeout")
                    .timeout(Duration.ofMillis(30))
                    .build());
            groupDeadline.addTask(
                    "slow",
                    global.par("worker"),
                    () -> {
                        Thread.sleep(10_000);
                        return 1;
                    },
                    MultiTaskOptions.of("slow").timeout(Duration.ofSeconds(2)).build());
            TaskGroupResult first =
                    groupDeadline.buildAndSubmitAll().completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(first.completionReason()).isEqualTo(TaskGroupCompletionReason.TIMEOUT);
            assertThat(first.members().get("slow").completionReason()).isEqualTo(TaskOutcome.TIMEOUT);

            ParallelTaskGroup.Builder memberDeadline = global.taskGroupBuilder(MultiTaskOptions.of("member-timeout")
                    .timeout(Duration.ofSeconds(2))
                    .build());
            memberDeadline.addTask(
                    "slow",
                    global.par("worker"),
                    () -> {
                        Thread.sleep(10_000);
                        return 1;
                    },
                    MultiTaskOptions.of("slow").timeout(Duration.ofMillis(30)).build());
            TaskGroupResult second =
                    memberDeadline.buildAndSubmitAll().completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(second.completionReason()).isEqualTo(TaskGroupCompletionReason.TIMEOUT);
            assertThat(second.members().get("slow").completionReason()).isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void directMemberCancellationCascadesToUnfinishedSibling() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        CountDownLatch release = new CountDownLatch(1);
        try {
            ParallelTaskGroup.Builder builder = global.taskGroupBuilder(MultiTaskOptions.of("member-cancel")
                    .timeout(Duration.ofSeconds(30))
                    .build());
            ParallelTaskGroup.TaskHandle<Integer> canceled = builder.addTask(
                    "canceled",
                    global.par("worker"),
                    () -> {
                        release.await();
                        return 1;
                    },
                    MultiTaskOptions.of("canceled")
                            .timeout(Duration.ofSeconds(30))
                            .build());
            ParallelTaskGroup.TaskHandle<Integer> sibling = builder.addTask(
                    "sibling",
                    global.par("worker"),
                    () -> {
                        release.await(10, TimeUnit.SECONDS);
                        return 2;
                    },
                    MultiTaskOptions.of("sibling")
                            .timeout(Duration.ofSeconds(30))
                            .build());
            ParallelTaskGroup group = builder.buildAndSubmitAll();
            canceled.future().cancel(true);

            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.completionReason()).isEqualTo(TaskGroupCompletionReason.CANCELED);
            assertThat(result.members().get("canceled").completionReason()).isEqualTo(TaskOutcome.MEMBER_CANCELED);
            assertThat(result.members().get("sibling").completionReason()).isEqualTo(TaskOutcome.GROUP_CANCELED);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void memberTimeoutEscalatesToGroupTimeoutAndCancelsSibling() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            ParallelTaskGroup.Builder builder = global.taskGroupBuilder(MultiTaskOptions.of("member-timeout")
                    .timeout(Duration.ofSeconds(2))
                    .build());
            builder.addTask(
                    "slow",
                    global.par("worker"),
                    () -> {
                        Thread.sleep(10_000);
                        return 1;
                    },
                    MultiTaskOptions.of("slow").timeout(Duration.ofMillis(50)).build());
            builder.addTask(
                    "sibling",
                    global.par("worker"),
                    () -> {
                        Thread.sleep(10_000);
                        return 2;
                    },
                    MultiTaskOptions.of("sibling")
                            .timeout(Duration.ofSeconds(30))
                            .build());

            TaskGroupResult result =
                    builder.buildAndSubmitAll().completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.completionReason()).isEqualTo(TaskGroupCompletionReason.TIMEOUT);
            assertThat(result.members().get("slow").completionReason()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.members().get("sibling").completionReason()).isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void explicitGroupCancellationClassifiesEveryUnfinishedMember() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        CountDownLatch started = new CountDownLatch(2);
        try {
            ParallelTaskGroup.Builder builder = global.taskGroupBuilder(MultiTaskOptions.of("cancel")
                    .timeout(Duration.ofSeconds(30))
                    .build());
            for (String name : Arrays.asList("one", "two")) {
                builder.addTask(
                        name,
                        global.par("worker"),
                        () -> {
                            started.countDown();
                            Thread.sleep(10_000);
                            return name;
                        },
                        MultiTaskOptions.of(name)
                                .timeout(Duration.ofSeconds(30))
                                .build());
            }
            ParallelTaskGroup group = builder.buildAndSubmitAll();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            group.cancel();
            group.cancel();
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.completionReason()).isEqualTo(TaskGroupCompletionReason.CANCELED);
            assertThat(result.members().values())
                    .extracting(TaskGroupMemberResult::completionReason)
                    .containsOnly(TaskOutcome.GROUP_CANCELED);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void inlineTaskObservesCompleteFrozenRegistry() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        GlobalPar global = GlobalPar.builder().register("direct", direct).build();
        AtomicReference<ParallelTaskGroup> published = new AtomicReference<>();
        AtomicReference<ParallelTaskGroup.Builder> builderRef = new AtomicReference<>();
        try {
            ParallelTaskGroup.Builder builder = global.taskGroupBuilder(MultiTaskOptions.of("inline")
                    .timeout(Duration.ofSeconds(30))
                    .build());
            builderRef.set(builder);
            AtomicReference<ParallelTaskGroup.TaskHandle<Integer>> second = new AtomicReference<>();
            builder.addTask(
                    "first",
                    global.par("direct"),
                    () -> {
                        assertThat(second.get().future()).isNotNull();
                        return 1;
                    },
                    MultiTaskOptions.of("first").timeout(Duration.ofSeconds(30)).build());
            second.set(builder.addTask(
                    "second",
                    global.par("direct"),
                    () -> 2,
                    MultiTaskOptions.of("second")
                            .timeout(Duration.ofSeconds(30))
                            .build()));

            published.set(builder.buildAndSubmitAll());

            assertThat(published.get().members().keySet()).containsExactly("first", "second");
            assertThat(published.get().completionFuture().get().completionReason())
                    .isEqualTo(TaskGroupCompletionReason.SUCCESS);
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    @Test
    void rejectionIsSubmissionFailureAndLaterPreparedTaskNeverRuns() throws Exception {
        ExecutorService rejecting = new RejectingExecutor();
        ExecutorService normal = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder()
                .register("reject", rejecting)
                .register("normal", normal)
                .build();
        AtomicInteger calls = new AtomicInteger();
        try {
            ParallelTaskGroup.Builder builder = global.taskGroupBuilder(MultiTaskOptions.of("rejection")
                    .timeout(Duration.ofSeconds(30))
                    .build());
            builder.addTask(
                    "rejected",
                    global.par("reject"),
                    () -> 1,
                    MultiTaskOptions.of("rejected")
                            .taskType(TaskType.IO_BOUND)
                            .timeout(Duration.ofSeconds(30))
                            .build());
            builder.addTask(
                    "later",
                    global.par("normal"),
                    calls::incrementAndGet,
                    MultiTaskOptions.of("later").timeout(Duration.ofSeconds(30)).build());

            TaskGroupResult result =
                    builder.buildAndSubmitAll().completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.completionReason()).isEqualTo(TaskGroupCompletionReason.FAILED);
            assertThat(result.members().get("rejected").completionReason()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.members().get("later").completionReason()).isEqualTo(TaskOutcome.FAIL_FAST);
            assertThat(calls).hasValue(0);
            assertThat(SubmissionScope.currentBatch()).isNull();
        } finally {
            global.close();
            rejecting.shutdownNow();
            normal.shutdownNow();
        }
    }

    @Test
    void listenerReceivesSnapshotOutsideCurrentTaskAndIsolatedFromFailure() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        AtomicReference<TaskGroupResult> observed = new AtomicReference<>();
        AtomicReference<TaskExecutionContext> current = new AtomicReference<>();
        GlobalPar global = GlobalPar.builder().register("direct", direct).build();
        try {
            MultiTaskOptions options = MultiTaskOptions.of("listener")
                    .listener(event -> {
                        observed.set(event.result());
                        current.set(TaskExecutionContext.current());
                        throw new IllegalStateException("ignored");
                    })
                    .timeout(Duration.ofSeconds(30))
                    .build();
            ParallelTaskGroup.Builder builder = global.taskGroupBuilder(options);
            builder.addTask(
                    "one",
                    global.par("direct"),
                    () -> 1,
                    MultiTaskOptions.of("one").timeout(Duration.ofSeconds(30)).build());

            TaskGroupResult result =
                    builder.buildAndSubmitAll().completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(observed.get()).isSameAs(result);
            assertThat(current.get()).isNull();
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    @Test
    void validatesDefinitionsAndEmptyGroupCompletesImmediately() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        GlobalPar foreign = GlobalPar.builder().register("foreign", executor).build();
        try {
            ParallelTaskGroup.Builder builder = global.taskGroupBuilder(MultiTaskOptions.of("validation")
                    .timeout(Duration.ofSeconds(30))
                    .build());
            builder.addTask(
                    "one",
                    global.par("worker"),
                    () -> 1,
                    MultiTaskOptions.of("one").timeout(Duration.ofSeconds(30)).build());
            assertThatThrownBy(() -> builder.addTask(
                            "one",
                            global.par("worker"),
                            () -> 2,
                            MultiTaskOptions.of("two")
                                    .timeout(Duration.ofSeconds(30))
                                    .build()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.addTask(
                            "foreign",
                            foreign.par("foreign"),
                            () -> 2,
                            MultiTaskOptions.of("foreign")
                                    .timeout(Duration.ofSeconds(30))
                                    .build()))
                    .isInstanceOf(IllegalArgumentException.class);

            ParallelTaskGroup empty = global.taskGroupBuilder(MultiTaskOptions.of("empty")
                            .timeout(Duration.ofSeconds(30))
                            .build())
                    .buildAndSubmitAll();
            assertThat(empty.completionFuture().get().completionReason()).isEqualTo(TaskGroupCompletionReason.SUCCESS);

            ParallelTaskGroup.Builder existing = global.taskGroupBuilder(MultiTaskOptions.of("existing")
                    .timeout(Duration.ofSeconds(30))
                    .build());
            global.close();
            assertThatThrownBy(() -> global.taskGroupBuilder(MultiTaskOptions.of("closed")
                            .timeout(Duration.ofSeconds(30))
                            .build()))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(existing::buildAndSubmitAll).isInstanceOf(IllegalStateException.class);
        } finally {
            foreign.close();
            executor.shutdownNow();
        }
    }

    @Test
    void nestedGroupCapturesOuterTaskAsStructuralParent() throws Exception {
        ExecutorService outer = Executors.newSingleThreadExecutor();
        ExecutorService inner = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder()
                .register("outer", outer)
                .register("inner", inner)
                .build();
        try {
            AsyncBatchResult<BatchExecutionContext> result = global.par("outer")
                    .map(
                            Arrays.asList(1),
                            ignored -> {
                                BatchExecutionContext expectedParent =
                                        TaskExecutionContext.current().batchContext();
                                ParallelTaskGroup.Builder builder =
                                        global.taskGroupBuilder(MultiTaskOptions.of("nested")
                                                .timeout(Duration.ofSeconds(30))
                                                .build());
                                ParallelTaskGroup.TaskHandle<BatchExecutionContext> child = builder.addTask(
                                        "child",
                                        global.par("inner"),
                                        () -> TaskExecutionContext.current()
                                                .batchContext()
                                                .parent(),
                                        MultiTaskOptions.of("child")
                                                .timeout(Duration.ofSeconds(30))
                                                .build());
                                builder.buildAndSubmitAll();
                                try {
                                    assertThat(child.future().get(2, TimeUnit.SECONDS))
                                            .isSameAs(expectedParent);
                                    return expectedParent;
                                } catch (Exception failure) {
                                    throw new RuntimeException(failure);
                                }
                            },
                            MultiTaskOptions.of("outer")
                                    .timeout(Duration.ofSeconds(30))
                                    .build());
            assertThat(result.results().get(0).get(2, TimeUnit.SECONDS)).isNotNull();
        } finally {
            global.close();
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    @Test
    void ancestorTimeoutPropagatesAsTimeoutIntoNestedGroup() throws Exception {
        ExecutorService outer = Executors.newSingleThreadExecutor();
        ExecutorService inner = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder()
                .register("outer", outer)
                .register("inner", inner)
                .build();
        AtomicReference<ParallelTaskGroup> nestedGroup = new AtomicReference<>();
        try {
            AsyncBatchResult<Object> result = global.par("outer")
                    .map(
                            Arrays.asList(1),
                            ignored -> {
                                ParallelTaskGroup.Builder builder =
                                        global.taskGroupBuilder(MultiTaskOptions.of("nested")
                                                .timeout(Duration.ofSeconds(30))
                                                .build());
                                builder.addTask(
                                        "child",
                                        global.par("inner"),
                                        () -> {
                                            Thread.sleep(10_000);
                                            return 1;
                                        },
                                        MultiTaskOptions.of("child")
                                                .timeout(Duration.ofSeconds(30))
                                                .build());
                                nestedGroup.set(builder.buildAndSubmitAll());
                                try {
                                    new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                                return null;
                            },
                            MultiTaskOptions.of("outer")
                                    .timeout(Duration.ofMillis(50))
                                    .build());

            // The outer batch deadline cancels the outer task and propagates into the nested
            // group's token tree; the group keeps the originating timeout reason.
            org.awaitility.Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> nestedGroup.get() != null);
            TaskGroupResult nested = nestedGroup.get().completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.results().get(0)).isCancelled();
            assertThat(nested.completionReason()).isEqualTo(TaskGroupCompletionReason.TIMEOUT);
            assertThat(nested.members().get("child").completionReason()).isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    @Test
    void groupIdentifiersExposeConfiguredAndGeneratedValues() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            ParallelTaskGroup group = global.taskGroupBuilder(MultiTaskOptions.of("named")
                            .timeout(Duration.ofSeconds(30))
                            .build())
                    .buildAndSubmitAll();
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(group.groupName()).isEqualTo("named");
            assertThat(result.groupName()).isEqualTo("named");
            assertThat(group.groupId()).isNotBlank();
            assertThat(result.groupId()).isEqualTo(group.groupId());
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeAfterCompletionIsNoopAndCloseCancelsUnfinishedMembers() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        CountDownLatch started = new CountDownLatch(1);
        try {
            ParallelTaskGroup completed = global.taskGroupBuilder(MultiTaskOptions.of("done")
                            .timeout(Duration.ofSeconds(30))
                            .build())
                    .buildAndSubmitAll();
            completed.completionFuture().get(2, TimeUnit.SECONDS);
            completed.close(); // must not disturb the recorded result
            assertThat(completed.completionFuture().get().completionReason())
                    .isEqualTo(TaskGroupCompletionReason.SUCCESS);

            ParallelTaskGroup.Builder builder = global.taskGroupBuilder(MultiTaskOptions.of("close-cancel")
                    .timeout(Duration.ofSeconds(30))
                    .build());
            builder.addTask(
                    "slow",
                    global.par("worker"),
                    () -> {
                        started.countDown();
                        Thread.sleep(10_000);
                        return 1;
                    },
                    MultiTaskOptions.of("slow").timeout(Duration.ofSeconds(30)).build());
            ParallelTaskGroup unfinished = builder.buildAndSubmitAll();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            unfinished.close();
            TaskGroupResult result = unfinished.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.completionReason()).isEqualTo(TaskGroupCompletionReason.CANCELED);
            assertThat(result.members().get("slow").completionReason()).isEqualTo(TaskOutcome.GROUP_CANCELED);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void outerBatchCancellationPropagatesIntoGroupAsGroupCancellation() throws Exception {
        ExecutorService outer = Executors.newSingleThreadExecutor();
        ExecutorService inner = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder()
                .register("outer", outer)
                .register("inner", inner)
                .build();
        try {
            AtomicReference<CancellationToken> outerToken = new AtomicReference<>();
            AtomicReference<ParallelTaskGroup> publishedGroup = new AtomicReference<>();
            AtomicReference<String> observedReason = new AtomicReference<>();
            CountDownLatch groupBuilt = new CountDownLatch(1);
            AsyncBatchResult<String> outerBatch = global.par("outer")
                    .map(
                            Arrays.asList("x"),
                            ignored -> {
                                outerToken.set(TaskExecutionContext.current()
                                        .batchContext()
                                        .cancellationToken());
                                ParallelTaskGroup.Builder builder =
                                        global.taskGroupBuilder(MultiTaskOptions.of("outer-cancel")
                                                .timeout(Duration.ofSeconds(30))
                                                .build());
                                builder.addTask(
                                        "slow",
                                        global.par("inner"),
                                        () -> {
                                            Thread.sleep(10_000);
                                            return 1;
                                        },
                                        MultiTaskOptions.of("slow")
                                                .timeout(Duration.ofSeconds(30))
                                                .build());
                                ParallelTaskGroup group = builder.buildAndSubmitAll();
                                publishedGroup.set(group);
                                groupBuilt.countDown();
                                // Stay inside the outer task until the group converges, so the
                                // outer batch token is still RUNNING when the test cancels it.
                                while (true) {
                                    try {
                                        String reason = group.completionFuture()
                                                .get()
                                                .completionReason()
                                                .name();
                                        // Record what the running task observed instead of
                                        // asserting on the outer future: the cancel cascade
                                        // hard-cancels bound futures right after the group
                                        // converges, so the task's return value races the
                                        // cancellation and is not a stable signal.
                                        observedReason.set(reason);
                                        return reason;
                                    } catch (InterruptedException interrupted) {
                                        // cancellation reached this task before the group settled;
                                        // keep waiting for the group's terminal reason
                                    } catch (java.util.concurrent.ExecutionException failure) {
                                        throw new RuntimeException(failure);
                                    }
                                }
                            },
                            MultiTaskOptions.of("outer")
                                    .timeout(Duration.ofSeconds(30))
                                    .build());
            assertThat(groupBuilt.await(2, TimeUnit.SECONDS)).isTrue();
            outerToken.get().cancel(true);
            ParallelTaskGroup group = publishedGroup.get();

            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.completionReason()).isEqualTo(TaskGroupCompletionReason.CANCELED);
            assertThat(result.members().get("slow").completionReason()).isEqualTo(TaskOutcome.GROUP_CANCELED);
            org.awaitility.Awaitility.await()
                    .atMost(2, TimeUnit.SECONDS)
                    .until(() -> observedReason.get() != null
                            && outerBatch.results().get(0).isDone());
            assertThat(observedReason.get()).isEqualTo("CANCELED");
        } finally {
            global.close();
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    private static final class RejectingExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown = true;
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("rejected");
        }
    }
}
