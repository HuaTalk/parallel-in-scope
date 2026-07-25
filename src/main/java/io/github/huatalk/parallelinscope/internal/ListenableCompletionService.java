package io.github.huatalk.parallelinscope.internal;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

/**
 * Completion service whose submitted runnable and returned future are the same object.
 *
 * @param <V> the result type of submitted tasks
 */
final class ListenableCompletionService<V> implements CompletionService<V> {

    private final Executor executor;
    private final BlockingQueue<ListenableFuture<V>> completionQueue;

    ListenableCompletionService(Executor executor) {
        this(executor, new LinkedBlockingQueue<>());
    }

    ListenableCompletionService(
            Executor executor,
            BlockingQueue<ListenableFuture<V>> completionQueue) {
        this.executor = Objects.requireNonNull(executor);
        this.completionQueue = Objects.requireNonNull(completionQueue);
    }

    @Override
    public ListenableFuture<V> submit(Callable<V> task) {
        return submitTask(ListenableFutureTask.create(task));
    }

    @Override
    public ListenableFuture<V> submit(Runnable task, @Nullable V result) {
        return submitTask(ListenableFutureTask.create(task, result));
    }

    @Override
    public ListenableFuture<V> take() throws InterruptedException {
        return completionQueue.take();
    }

    @Override
    public ListenableFuture<V> poll() {
        return completionQueue.poll();
    }

    @Override
    public ListenableFuture<V> poll(long timeout, TimeUnit unit) throws InterruptedException {
        return completionQueue.poll(timeout, unit);
    }

    private ListenableFuture<V> submitTask(ListenableFutureTask<V> task) {
        task.addListener(() -> completionQueue.add(task), directExecutor());
        executor.execute(task);
        return task;
    }
}
