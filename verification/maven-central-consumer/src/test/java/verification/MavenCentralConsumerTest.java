package verification;

import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.Par;
import io.github.huatalk.parallelinscope.scope.ParConfig;
import io.github.huatalk.parallelinscope.scope.ParOptions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenCentralConsumerTest {

    @Test
    void publishedArtifactCanBeResolvedAndUsed() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ParConfig config = ParConfig.builder()
                    .executor("consumer-pool", executor)
                    .build();

            AsyncBatchResult<Integer> result = new Par(config).map(
                    "consumer-pool",
                    Arrays.asList(1, 2),
                    value -> value * 2,
                    ParOptions.ioTask("consumer-smoke").parallelism(2).build());

            assertEquals(2, result.getResults().get(0).get());
            assertEquals(4, result.getResults().get(1).get());
        } finally {
            executor.shutdownNow();
        }
    }
}
