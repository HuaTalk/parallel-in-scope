package demo.article;

import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.Par;
import io.github.huatalk.parallelinscope.scope.ParConfig;
import io.github.huatalk.parallelinscope.scope.ParOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2. 批量结果统计——一行代码拿到全貌
 *
 * <p>问题：原生 Future 需要逐个检查 isDone / isCancelled / get + catch，
 * 代码冗长且容易遗漏统计维度。
 *
 * <p>解决：AsyncBatchResult.reportString() 一行拿到全貌，
 * report() 返回结构化 BatchReport 供精确查询。
 */
class G2_BatchResultReportTest {

    private ExecutorService pool;
    private Par par;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(4);
        ParConfig config = ParConfig.builder()
                .executor("test-pool", pool)
                .build();
        par = new Par(config);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /**
     * 问题复现：原生 Future 手动统计——逐个检查 isDone / isCancelled / get + catch，
     * 超过 20 行才能拿到成功/失败/取消的概览。
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void problem_manualResultAggregationIsVerbose() throws Exception {
        // 构造 10 个任务：能被 3 整除的抛异常（0,3,6,9），其余正常返回
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int val = i;
            futures.add(pool.submit(() -> {
                if (val % 3 == 0) {
                    throw new RuntimeException("task-" + val + " failed");
                }
                return val * 2;
            }));
        }

        // 等待所有任务完成
        for (Future<Integer> f : futures) {
            try {
                f.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException ignored) {
                // 预期的失败，继续
            }
        }

        // 手动统计：逐个检查 isDone / isCancelled / get + catch
        int success = 0;
        int failed = 0;
        int cancelled = 0;
        Throwable firstError = null;
        for (Future<Integer> f : futures) {
            if (f.isCancelled()) {
                cancelled++;
            } else if (f.isDone()) {
                try {
                    f.get();
                    success++;
                } catch (ExecutionException e) {
                    failed++;
                    if (firstError == null) {
                        firstError = e.getCause();
                    }
                }
            }
        }

        // 验证手动统计结果：6 成功, 4 失败（0,3,6,9）, 0 取消
        assertThat(success).isEqualTo(6);
        assertThat(failed).isEqualTo(4);
        assertThat(cancelled).isZero();
        assertThat(firstError).isNotNull();
        assertThat(firstError).isInstanceOf(RuntimeException.class);
    }

    /**
     * 解决方案：Par.map() + AsyncBatchResult.reportString()，
     * 一行代码拿到 "SUCCESS:6,FAILED:4" 的全貌。
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void solution_reportStringGivesInstantSummary() throws Exception {
        ParOptions opts = ParOptions.of("batch-report")
                .parallelism(4)
                .timeout(5000)
                .build();

        // 构造同样的 10 个任务：0,3,6,9 抛异常，其余正常返回
        List<Integer> items = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        AsyncBatchResult<Integer> result = par.map("test-pool", items, x -> {
            if (x % 3 == 0) {
                throw new RuntimeException("task-" + x + " failed");
            }
            return x * 2;
        }, opts);

        // 等待所有任务完成
        for (int i = 0; i < result.getResults().size(); i++) {
            try {
                result.getResults().get(i).get(10, TimeUnit.SECONDS);
            } catch (ExecutionException ignored) {
                // 预期的失败，继续统计
            } catch (CancellationException ignored) {
                // 预期的取消，继续统计
            }
        }

        // 一行拿到全貌
        String reportStr = result.reportString();
        assertThat(reportStr)
                .as("reportString() 应包含 SUCCESS 和 FAILED 统计")
                .contains("SUCCESS:6", "FAILED:4");

        // 结构化访问：获取首个异常
        AsyncBatchResult.BatchReport report = result.report();
        assertThat(report.getFirstException())
                .as("report() 应返回第一个失败任务的异常")
                .isNotNull()
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failed");

        // 验证状态计数 map 大小（SUCCESS + FAILED = 2 种状态）
        assertThat(report.getStateCounts())
                .as("应恰好包含 SUCCESS 和 FAILED 两种状态")
                .hasSize(2);
    }
}
