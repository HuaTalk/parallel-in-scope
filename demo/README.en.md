[**Chinese**](README.md) | **English**

# parallel-in-scope-demo

An independent consumer project containing runnable examples for `parallel-in-scope`.

## Build and Run

Install the library from the repository root, then build the demo:

```bash
mvn install -DskipTests -Dmaven.javadoc.skip=true
mvn -f demo/pom.xml test
mvn -f demo/pom.xml exec:java
```

Run a specific example:

```bash
mvn -f demo/pom.xml -Dexec.mainClass=demo.basic.BasicParDemo exec:java
mvn -f demo/pom.xml -Dexec.mainClass=demo.basic.CancellationDemo exec:java
mvn -f demo/pom.xml -Dexec.mainClass=demo.basic.InterruptibleTaskDemo exec:java
mvn -f demo/pom.xml -Dexec.mainClass=demo.advanced.DeadlockDetectionDemo exec:java
mvn -f demo/pom.xml -Dexec.mainClass=demo.integration.BatchProcessingDemo exec:java
```

## Architecture Boundary

The demo depends on the published library artifact and acts as an external consumer:

```text
demo -> io.github.huatalk:parallel-in-scope
```

Examples use the `demo.*` namespace and access public APIs from `scope`, `spi`, and the public `cancel.Checkpoints` and `cancel.CancellationChecker` utilities. Internal implementation packages are intentionally excluded.

## Documentation

- [English documentation map](docs/en/README.md)
- [Chinese article catalog](docs/zh-CN/README.md)
- [Architecture constraints](architecture-constraints.md) (Chinese)
- [Project documentation](../docs/en/index.md)
