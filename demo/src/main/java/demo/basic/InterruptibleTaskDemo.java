package demo.basic;

import io.github.huatalk.parallelinscope.cancel.Checkpoints;
import io.github.huatalk.parallelinscope.cancel.CancellationChecker;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 可中断任务的最小示例：原生中断、按次数检查点和按时间检查点。
 */
public final class InterruptibleTaskDemo {

    private InterruptibleTaskDemo() {
    }

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = pool.submit(() -> runTask(Integer.MAX_VALUE, 10_000, 50));
            Thread.sleep(100);
            task.cancel(true); // 设置取消状态，并尝试 interrupt 执行线程
            System.out.println("task cancelled: " + task.isCancelled());
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    /**
     * CPU 循环不自动响应 interrupt，必须在合适频率主动检查。
     */
    public static long runTask(int limit, int checkEvery, long checkIntervalMillis) {
        long sum = 0;
        CancellationChecker checker = CancellationChecker.whenBoth(
                checkEvery,
                Duration.ofMillis(checkIntervalMillis),
                Checkpoints::rawCheckpoint);
        for (int i = 0; i < limit; i++) {
            sum += i;
            checker.check();
        }
        return sum;
    }
}
