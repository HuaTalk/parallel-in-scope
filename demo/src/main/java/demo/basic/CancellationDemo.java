package demo.basic;

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
 * 取消机制示例：演示任务取消的基本用法
 *
 * <p>这个示例展示了如何使用 parallel-in-scope 的超时机制实现任务取消：
 * <ul>
 *   <li>配置超时时间</li>
 *   <li>在任务中处理超时</li>
 *   <li>查看执行结果</li>
 * </ul>
 *
 * <p>注意：CancellationToken 是内部实现，不建议直接使用。
 * 推荐使用 ParOptions 的 timeout 配置来控制任务超时。
 */
public class CancellationDemo {

    public static void main(String[] args) {
        System.out.println("=== CancellationDemo ===");
        System.out.println("演示任务超时取消机制\n");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        ParConfig config = ParConfig.builder()
                .executor("cancel-demo", pool)
                .build();
        Par par = new Par(config);

        try {
            List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

            // 1. 配置并行选项（设置超时）
            ParOptions options = ParOptions.of("cancel-demo")
                    .parallelism(3)
                    .timeout(2000)
                    .build();

            System.out.println("开始并行处理（2秒超时）...");
            System.out.println("并行度: " + options.getParallelism());

            // 2. 执行并行处理
            long startTime = System.currentTimeMillis();
            AsyncBatchResult<Integer> result = par.map("cancel-demo", numbers, n -> {
                String threadName = Thread.currentThread().getName();

                System.out.println("  [" + threadName + "] 处理: " + n);

                // 模拟耗时操作
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("  [" + threadName + "] 任务 " + n + " 被中断");
                    return -1;
                }
                return n * n;
            }, options);

            long endTime = System.currentTimeMillis();

            // 3. 查看结果
            System.out.println("\n处理完成!");
            System.out.println("耗时: " + (endTime - startTime) + " 毫秒");
            System.out.println("执行报告: " + result.reportString());

        } finally {
            pool.shutdownNow();
        }
    }
}
