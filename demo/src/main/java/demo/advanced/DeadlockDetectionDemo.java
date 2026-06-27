package demo.advanced;

import io.github.huatalk.parallelinscope.scope.Par;
import io.github.huatalk.parallelinscope.scope.ParOptions;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.ParConfig;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 高级示例：演示死锁检测功能
 *
 * <p>这个示例展示了 parallel-in-scope 的死锁检测机制：
 * <ul>
 *   <li>嵌套任务执行</li>
 *   <li>任务依赖关系记录</li>
 *   <li>死锁检测和报告</li>
 * </ul>
 *
 * <p>注意：这是高级功能，通常在复杂嵌套场景中使用。
 */
public class DeadlockDetectionDemo {

    public static void main(String[] args) {
        System.out.println("=== DeadlockDetectionDemo ===");
        System.out.println("演示死锁检测功能\n");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        ParConfig config = ParConfig.builder()
                .executor("deadlock-demo", pool)
                .build();
        Par par = new Par(config);

        try {
            // 1. 模拟嵌套任务场景
            System.out.println("创建嵌套任务结构...");
            System.out.println("（此示例演示正常执行，实际死锁检测在复杂场景中触发）\n");

            List<String> tasks = Arrays.asList("任务A", "任务B", "任务C");

            ParOptions options = ParOptions.of("deadlock-demo")
                    .parallelism(2)
                    .build();

            // 2. 执行任务（并行处理会自动记录任务依赖）
            AsyncBatchResult<String> result = par.map("deadlock-demo", tasks, task -> {
                String threadName = Thread.currentThread().getName();
                System.out.println("  [" + threadName + "] 执行: " + task);

                // 模拟一些处理时间
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return task + "(中断)";
                }

                return task + "(完成)";
            }, options);

            // 3. 查看结果
            System.out.println("\n执行完成!");
            System.out.println("结果: " + result.reportString());

            System.out.println("\n注意: 死锁检测功能在复杂嵌套场景中自动触发。");
            System.out.println("当检测到潜在死锁时，框架会抛出异常并报告死锁链。");

        } finally {
            pool.shutdownNow();
        }
    }
}
