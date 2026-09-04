# coding agent 的「表面修复」：一个取消 bug 的三次修复复盘

大家好，我是桦说编程。

> 我把一次真实事故（框架取消代码手改引入 bug）复原成评测现场，让 codex + gpt-5.6-sol 自主修复。结果：测试全绿、契约完成度很高，但根因没找到，修复只是面上的。本文记录三次修复版本，以及测试全绿为何仍然掩盖了机制错误。

## 前置知识点

### Future 基础

1. **Future 是一次性结果占位符。** 每个 future 只完成一次。
2. **一个 future 有三种终态：成功、失败、取消。**
3. **取消是终态之一，但不是失败。** 两者在代码里分属不同路径。

### ListenableFuture 与组合

4. **ListenableFuture = Future + 完成回调。** 到达任一终态时，触发预先挂上的回调。
5. **组合器把多个 future 合成一个新的。** 合成的 future 代表「这一组」的整体结果。
6. **`Futures.allAsList`：全部成功才成功，任一失败则整体失败。**
7. **`withTimeout`：到点未完成，整体以超时失败结束。**

### 取消的传播

8. **cancel 会沿组合结构传播。** 取消合成的 future，会波及合成它的下层。
9. **对已完成的 future 调 cancel 无效。** 返回 false，不做任何事。
10. **取消有方向。** 从链的顶层句柄取消，和从底层某个任务取消，传播路径不同，会触发链上不同的代码。
11. **`catchingAsync` 的 fallback 只在输入失败时执行。** 输入以失败结束时，fallback 接管；输入成功或被取消时，走各自路径。
12. **`addCallback` 的失败回调对取消也触发。** 输入被取消时，`onFailure` 收到一个 `CancellationException`。

### 框架层：取消令牌与结构化并发

13. **取消令牌（token）是一组任务的管理句柄。** 组内所有任务共享这一个 token。
14. **token 有状态机。** 终态包括：成功、超时、组员失败（fail-fast）、被外部取消。
15. **bind = 提交后接线。** 任务跑起来后，把 token 挂到实际的 future 链上，此后 token 才能代表整组取消。
16. **fail-fast：组内任一任务失败 → 取消组内其余任务。**
17. **组超时 → 取消整组。**
18. **token 被外部取消 → 取消整组。**
19. **成员 future 可以被单独取消，不经 token。** 调用方直接 cancel 单个任务的 future 即可。
20. **归因靠状态对齐。** 一个成员被取消后，读 token 与成员的状态，判定是超时、fail-fast、外部取消还是被直接取消。

结构总览：

```text
取消令牌（组的管理句柄）
    │  bind()：提交后把令牌挂到任务链上
    ▼
组合链（等全部任务 + 超时 + 兜底回调）
    │  取消沿链传导
    ▼
任务 A    任务 B    任务 C     ← 真正跑用户代码的地方
```

## 问题背景

我在维护一个基于 Guava `ListenableFuture` 的结构化并发框架（`parallel-in-scope`）。一次重构中，我把取消令牌的 `bind()` 从 `addCallback` 风格重写为 `FluentFuture` 链式风格：

```java
ListenableFuture<?> listCanceller = Futures.allAsList(futures);
FluentFuture<?> failFastFuture = FluentFuture.from(listCanceller)
        .withTimeout(timeout, timer)
        .transform(ignored -> state.compareAndSet(RUNNING, SUCCESS), directExecutor())
        .catchingAsync(Throwable.class, ex -> {
            if (ex instanceof TimeoutException) state.compareAndSet(RUNNING, TIMEOUT_CANCELED);
            else                              state.compareAndSet(RUNNING, FAIL_FAST_CANCELED);
            submitCanceller.cancel(true);
            listCanceller.cancel(true);
            return Futures.immediateCancelledFuture();
        }, directExecutor());
futureToken.setFuture(failFastFuture);
```

看起来无懈可击：成员失败 → 取消所有任务；超时 → 取消所有任务。但这次手改引入了两个隐藏 bug，当时测试没暴露，后续 agent 按契约扩展功能时才撞上红灯。

## 复原现场与评测方法

为了让不同 coding agent 公平试跑，我建了一个自包含的评测包：

- git bundle 快照：仓库冻结在引入 bug 的那个提交，bundle 只含该提交及其祖先，之后的任何修复提交物理上不可见
- handoff 契约：完整的实现规格（deadline 语义、归因表、竞态注意点），agent 直接 pickup
- 参考答案外置：真解留在主仓库历史里，评测人可见、agent 不可见

现场自带红灯：21 个相关测试里 5 失败 1 错误，正好对应这两个 bug。

## codex (gpt-5.6-sol) 的表现：任务完成，根因未命中

codex 在 yolo 模式下自主跑了约 40 分钟，输出单 commit（+657/-234，15 文件）。我独立复跑确认 410/410 全绿，deadline 进 token、group 接线、超时升级、文档更新全部完成。契约执行层面近乎满分。

看它怎么修的取消路径：

```java
// codex 版：保留 catchingAsync 原结构，新增补偿字段
private @Nullable volatile List<ListenableFuture<?>> boundFutures;
private @Nullable volatile ListenableFuture<?> submitCanceller;

public void cancel(boolean useInterrupt) {
    transitionTo(MUTUAL_CANCELED);
    futureToken.cancel(useInterrupt);
    cancelWork(useInterrupt);          // ← 手动再取消一遍
}

private void cancelWork(boolean useInterrupt) {
    ListenableFuture<?> canceller = submitCanceller;
    if (canceller != null) canceller.cancel(useInterrupt);
    List<ListenableFuture<?>> futures = boundFutures;
    if (futures != null) {
        for (ListenableFuture<?> future : futures) future.cancel(useInterrupt);
    }
}
```

它观察到「取消时任务和 submitter 没被取消」的现象，修法是：每个取消入口都直接遍历 cancel 一遍原始 future。测试绿了，但问题没真正解决。

`futureToken.cancel()` 本就会沿组合链级联取消任务，`cancelWork` 再手动 cancel 一遍，等于双份取消，两条路径各自为政；出问题的 catchingAsync 结构也被原样保留，根因没有真正被理解，只是用补偿逻辑绕开。它自己在完成报告里还承认了一个未决竞态（状态监听注册与状态迁移无同步），并论证「当前流程不可达」。

## 有意思的对照：人类 agent 的初修也是「表面修复」

在第一轮（本会话的初版修复），Claude 同样没第一时间到根因，修法是给 token 加 `submitCanceller` 字段 + 两处 synchronized + 补偿逻辑（`storeSubmitCanceller`/`cancelBoundWork`），用锁保证「取消方读到字段」与「绑定方读到终态」至少发生一个。代码能跑、测试全绿，但锁加双保险补偿的复杂度，我当时就直接判定为差设计。

两个 agent 走的弯路是同一条：看到现象，在现象层加补偿，测试一绿就收工。

## 根因：Guava 组合 future 的取消语义

读 Guava 33.6 源码（`AbstractCatchingFuture.run()`）：

```java
if ((localInputFuture == null | localExceptionType == null | localFallback == null)
        || isCancelled()) {   // ← 检查的是输出 future 自己
    return;                   //     fallback 直接不跑
}
```

语义一，从输出侧传播的取消不会触发 `catchingAsync` 的 fallback。取消链路是：`futureToken.cancel()` 级联先把 catchingAsync 的输出置为 cancelled，之后输入完成触发 `run()`，`isCancelled()` 为真直接早退，fallback 里的 `submitCanceller.cancel(true)` 成了死代码。这就是「手动取消不取消 submitter」的根因。

```java
sourceResult = getDone(localInputFuture);        // cancelled 输入 → 抛 CancellationException
} catch (Throwable t) { // this includes CancellationException  ← 源码原注释
    throwable = t;
}
// CancellationException instanceof Throwable.class → fallback 会执行
```

语义二，从输入侧（成员 future 被直接取消）传播的取消反而会触发 fallback：`CancellationException` 被 `catch (Throwable)` 接住，匹配 `Throwable.class`，`doFallback` 执行。所以同一个 catchingAsync，两个方向的取消行为完全相反：

```text
top-down token.cancel:     catchingAsync fallback 不执行  ← bug 2
bottom-up future.cancel:   catchingAsync fallback 执行     ← 恰好当 fail-fast 用
```

还有语义三，藏在 fail-fast 路径里：成员失败时 `allAsList` 当场已经 failed，是终态，对已完成的 future 调 `cancel()` 只返回 false，什么都不做。`AggregateFuture` 只在组合 future 自己还 pending 时才会把取消级联给输入，所以「fail-fast 后 cancel listCanceller」也是死代码（bug 1）。只有 timeout 路径恰好能用，因为超时时组合 future 还 pending。

## 终态修复：恢复 addCallback，删掉全部补偿

旧实现 `addCallback` 没有 `isCancelled` 早退。`CallbackListener` 对 cancelled future 执行 `getUninterruptibly`，抛出的 `CancellationException` 被当 RuntimeException 捕获，路由进 `onFailure`。也就是说旧 API 恰好两个方向都会触发回调：

```java
FluentFuture<?> failFastFuture =
        FluentFuture.from(Futures.allAsList(futures)).withTimeout(remaining, timer);
// pending 的 successfulAsList 是唯一能同时覆盖任务与 submitter 的可取消句柄：
// 它要等全部输入完成才终态，所以某个任务失败/取消后 cancel 它仍能传播
ListenableFuture<?> allFutures = Futures.successfulAsList(Futures.successfulAsList(futures), submitCanceller);
failFastFuture.addCallback(new FutureCallback<Object>() {
    @Override public void onSuccess(Object result) { transitionTo(SUCCESS); }
    @Override public void onFailure(Throwable failure) {
        transitionTo(failure instanceof TimeoutException ? TIMEOUT_CANCELED : FAIL_FAST_CANCELED);
        allFutures.cancel(true);       // 手动/超时/fail-fast/成员直消，一条路径全覆盖
    }
}, directExecutor());
```

一个回调覆盖四个方向（手动 cancel、timeout、fail-fast、成员直消），零补偿字段、零锁，净减数十行。两个 agent 各花了数百行补偿，都没走到这一步，因为根因藏在依赖库的隐性语义里，而测试全绿又给了它们「已经完成」的信号。

## 复现（可运行，纯 Guava）

```java
import com.google.common.util.concurrent.*;
import java.util.concurrent.*;

public class SemanticsDemo {
    public static void main(String[] args) throws Exception {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        // 场景 A：failed 的 allAsList，cancel 它不再级联（bug 1 的机制）
        SettableFuture<Integer> failed = SettableFuture.create();
        SettableFuture<Integer> sibling = SettableFuture.create();
        ListenableFuture<?> listCanceller = Futures.allAsList(failed, sibling);
        failed.setException(new RuntimeException("boom"));
        listCanceller.cancel(true);                        // 已经 failed，cancel 是 no-op
        System.out.println("A. sibling cancelled after fail-fast: " + sibling.isCancelled()); // false

        // 场景 B：取消传播到 catchingAsync 时 fallback 是否执行（bug 2 的机制）
        SettableFuture<Integer> task = SettableFuture.create();
        FluentFuture<?> guarded = FluentFuture.from(Futures.allAsList(task))
                .catchingAsync(Throwable.class, ex -> {
                    System.out.println("B. fallback ran (cause=" + ex + ")");
                    return Futures.immediateCancelledFuture();
                }, MoreExecutors.directExecutor());
        SettableFuture<Object> cancelFromTop = SettableFuture.create();
        cancelFromTop.setFuture(guarded);                  // 模拟 token.cancel 级联
        cancelFromTop.cancel(true);
        Thread.sleep(50);
        System.out.println("B. fallback after top-down cancel: (see above)");
        timer.shutdownNow();
    }
}
```

## 评测方法论教训

- 测试全绿不等于根因正确。两个 agent 的测试都是自洽的，它们按自己的错误归因写断言，绿灯反而把错误语义固化下来。这正是 SWE-bench 用 hidden tests 的原因：让 agent 无法自我验证，答案只在裁判手里
- self-report 必须独立复核。codex 报告「410 tests, 0 failures」属实，我复跑确认过，但「已完成」的结论建立在没到根因的基础上，agent 不知道自己不知道
- 补偿式修复是 agent 的默认路线：观测到现象，在现象层加逻辑，测试绿了，收工。人审的价值不在看测试，在看「机制是否被理解」：为什么补偿字段能删掉？
- 契约驱动开发会放大这个缺陷。handoff 把需求写得很细，agent 完美执行契约，但契约里没写「理解 Guava 取消语义」。隐性知识不进入契约，agent 就不会去覆盖
- 评测要配参考答案对照。没有终态真解做基准，你无法区分「绿了但没修到根」和「绿且根因正确」

## 总结

这次评测看下来，gpt-5.6-sol 这个级别的 coding agent，强项在任务面：契约执行、测试补齐、文档同步、代码组织都做得很干净，410 绿加单 commit、设计自洽，工程完成度确实高。弱项在机制面：组合 future 的取消传播方向、fallback 的触发条件，这类隐性语义藏在依赖库里，它们不会去读源码深挖，倾向用补偿逻辑让现象消失。

对使用者来说，把「测试绿」当最低标准，把「能不能删掉补偿逻辑」当评审问题：能删，说明根因找到了；不能删，说明只是面上修好了。对评测体系设计者来说，hidden tests、参考答案对照、代码评审三个都要，缺一个，自洽的绿灯就会骗过你。

---

如果这篇文章对你有帮助，欢迎关注我，持续分享高质量技术干货，助你更快提升编程能力。
