package io.github.huatalk.parallelinscope.spi;

/**
 * SPI: Logging abstraction for the parallel-in-scope framework.
 * <p>
 * Default implementation delegates to {@link java.util.logging.Logger}.
 * Users can provide their own implementation (e.g. bridging to SLF4J, Log4j2)
 * via {@code Par.setLogger(ParallelLogger)}.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public interface ParallelLogger {

    void debug(String message, Object... args);

    void warn(String message, Object... args);

    void error(String message, Object... args);
}
