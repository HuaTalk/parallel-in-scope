# 如何编写可中断的任务（简单好上手）

可中断任务的关键不是“强行杀死线程”，而是让任务在安全位置主动配合停止。把这个过程拆成三个原子动作：**发出信号、设置检查点、收尾退出**。

## 1. 原生 Java 支持什么

`Future.cancel(true)` 会尝试调用 `Thread.interrupt()`。它不是强制终止：

- `sleep`、`wait`、`join`、阻塞队列等阻塞调用通常会抛出 `InterruptedException`；
- CPU 密集型的紧密循环不会自动停，必须主动检查 `Thread.isInterrupted()` 或检查点；
- 中断是一次性的协作信号，业务代码决定在哪里结束。

最小的原生写法如下：

```java
try {
    while (!Thread.currentThread().isInterrupted()) {
        doOneUnit();
    }
} catch (InterruptedException e) {
    Thread.currentThread().interrupt(); // 恢复标志，交给上层处理
    cleanup();
}
```

不要把 `InterruptedException` 静默吞掉，也不要在 `catch` 后继续做大量工作。

## 2. 用项目的 Checkpoints 统一语义

本项目的 `Checkpoints` 把取消 token 和线程中断统一为同一种协作式检查：

```java
for (int i = 0; i < limit; i++) {
    result += compute(i);
    Checkpoints.rawCheckpoint();
}
```

`rawCheckpoint()` 在没有任务上下文时也能工作；如果当前 scope 已取消，或线程已被中断，它会抛出取消异常，从而取消后续执行。阻塞等待优先使用 `Checkpoints.sleep(...)`、`checkGet(...)` 等适配器，它们会把 `InterruptedException` 转成统一的取消异常。

## 3. 检查点不要放得太密

每次循环都检查会增加开销。实际项目通常按**次数或时间**触发，也可以组合两种条件：

```java
CancellationChecker checker = CancellationChecker.whenBoth(
        10_000,
        Duration.ofMillis(50),
        Checkpoints::rawCheckpoint);
for (int i = 0; i < limit; i++) {
    result += compute(i);
    checker.check();
}
```

`CancellationChecker` 通过 `Runnable` 绑定检查动作，因此不直接依赖 `Checkpoints`。除了上面的无参 `check()`，也可以使用 `shouldCheck()` 自行判断，或者通过 `checkIfDue(Runnable)` 在调用时传入不同的检查形式。

纯次数模式使用内部倒计数，每轮只做减一和比较，不读取系统时钟。时间模式接收 `Duration`，避免业务代码直接处理纳秒换算。组合模式使用 **AND** 语义：累计 N 次之前只更新倒计数；次数条件满足后保持该状态，并在后续调用中检查时间，距离上次检查也超过 `Duration` 时立即返回 `true`。成功触发后才同时重置次数和时间窗口。因此 `(200, 10ms)` 在第 200 次时若只有 9ms，会在第 201 次且时间达到 10ms 时触发，不会等到第 400 次。具体参数应根据可接受的取消延迟和基准测试调整。

## 4. 取消异常怎么处理

取消是正常控制流，不要把它记录成业务错误：

```java
import java.util.concurrent.CancellationException;

try {
    runTask();
} catch (CancellationException cancelled) {
    // 在任务边界记录“已取消”，释放本地资源，然后结束
    cleanup();
}
```

如果直接使用原生阻塞 API，则只捕获 `InterruptedException`，并在捕获后调用 `Thread.currentThread().interrupt()`。如果使用 `Checkpoints` 适配器，让取消异常向任务边界传播即可；批量场景可以通过 `AsyncBatchResult.reportString()` 查看最终状态。

## 5. 一个可运行的完整例子

下面的 demo 会先提交 CPU 循环，再调用 `cancel(true)`。循环按次数和时间触发检查点，因此会在下一次检查时结束，而不是等到自然跑完：

> [InterruptibleTaskDemo.java：当前项目 demo 的完整源码](https://github.com/huatalk/parallel-in-scope/blob/main/demo/src/main/java/demo/basic/InterruptibleTaskDemo.java)
>
> [CancellationChecker.java：核心项目中的检查频率工具类](https://github.com/huatalk/parallel-in-scope/blob/main/src/main/java/io/github/huatalk/parallelinscope/cancel/CancellationChecker.java)

```bash
cd demo
mvn -q -DskipTests compile
mvn -q -Dexec.mainClass=demo.basic.InterruptibleTaskDemo exec:java
```

配套测试也验证了“中断后在下一个检查点抛出异常”的行为：
[InterruptibleTaskDemoTest.java](https://github.com/huatalk/parallel-in-scope/blob/main/demo/src/test/java/demo/article/InterruptibleTaskDemoTest.java)。

## 6. 最后检查清单

1. 发起取消时使用 `cancel(true)` 或取消 scope。
2. CPU 循环插入按次数或按时间触发的检查点。
3. 阻塞操作使用可中断 API，捕获原生异常后恢复中断标志。
4. 取消异常在任务边界处理，不伪装成业务失败。
5. 在 `finally` 中释放资源，并用测试验证任务确实停止。
