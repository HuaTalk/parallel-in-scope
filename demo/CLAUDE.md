# CLAUDE.md - parallel-in-scope-demo

## 项目说明

parallel-in-scope 的独立示例项目，演示如何使用该并发工具库。

**重要**: 这是一个完全独立的子项目，与主项目解耦。

## 核心设计约束

### 1. 依赖方向（单向依赖）

```
demo (消费者) → parallel-in-scope (发布版本)
```

- **允许**: demo 依赖 parallel-in-scope 的发布版本（Maven Central）
- **禁止**: demo 依赖 parallel-in-scope 的源代码
- **禁止**: parallel-in-scope 依赖 demo

### 2. 包访问限制

#### 允许访问的包（公共 API）

| 包名 | 说明 |
|------|------|
| `io.github.huatalk.parallelinscope.scope` | 核心 API（Par, ParOptions, AsyncBatchResult, ParConfig） |
| `io.github.huatalk.parallelinscope.spi` | 扩展点（TaskListener, ExecutorResolver） |

#### 允许的例外类

| 类名 | 说明 |
|------|------|
| `io.github.huatalk.parallelinscope.cancel.Checkpoints` | 协作式取消的用户 API |

#### 禁止访问的包（内部实现）

| 包名 | 说明 |
|------|------|
| `io.github.huatalk.parallelinscope.internal` | 内部实现细节 |
| `io.github.huatalk.parallelinscope.cancel` | 取消机制内部实现（**Checkpoints 除外**） |
| `io.github.huatalk.parallelinscope.context` | 上下文传播内部实现 |
| `io.github.huatalk.parallelinscope.context.graph` | 死锁检测内部实现 |
| `io.github.huatalk.parallelinscope.queue` | 调度队列内部实现 |

### 3. 包命名约定

demo 子项目使用独立的包命名空间：

```
demo/
├── basic/          # 基础示例
├── advanced/       # 高级示例
└── integration/    # 集成示例
```

**禁止使用**: `io.github.huatalk.parallelinscope.*` 包名

## 目录结构

```
demo/
├── pom.xml                           # Maven 配置（依赖发布版本）
├── CLAUDE.md                         # 本文件
├── architecture-constraints.md       # 架构约束详细说明
├── scripts/
│   └── run-demos.sh                  # 运行脚本
└── src/
    ├── main/java/demo/
    │   ├── basic/                    # 基础示例
    │   │   ├── BasicParDemo.java     # Par.map() 基本用法
    │   │   └── CancellationDemo.java # 取消机制示例
    │   ├── advanced/                 # 高级示例
    │   │   └── DeadlockDetectionDemo.java  # 死锁检测示例
    │   └── integration/              # 集成示例
    │       └── BatchProcessingDemo.java    # 批量处理示例
    └── test/java/demo/
        ├── BasicParDemoTest.java            # 基础测试
        └── ArchitectureConstraintsTest.java # 架构约束测试
```

## 构建命令

```bash
# 编译项目
mvn clean compile

# 运行测试
mvn test

# 运行基础示例
mvn exec:java

# 运行特定示例
mvn exec:java -Dexec.mainClass=demo.basic.BasicParDemo
mvn exec:java -Dexec.mainClass=demo.basic.CancellationDemo
mvn exec:java -Dexec.mainClass=demo.advanced.DeadlockDetectionDemo
mvn exec:java -Dexec.mainClass=demo.integration.BatchProcessingDemo

# 运行所有示例（使用 profile）
mvn verify -Prun-all-demos
```

## 运行示例

```bash
# 使用脚本运行（推荐）
chmod +x scripts/run-demos.sh
./scripts/run-demos.sh basic
./scripts/run-demos.sh all
./scripts/run-demos.sh test

# 直接运行 Java 类
java -cp target/classes:target/dependency/* demo.basic.BasicParDemo
```

## Demo 说明

| Demo | 包 | 功能 |
|------|-----|------|
| BasicParDemo | basic | 演示 Par.map() 基本用法 |
| CancellationDemo | basic | 演示协作式取消（Checkpoints.sleep + 超时触发） |
| DeadlockDetectionDemo | advanced | 演示线程池嵌套调用死锁（A→B 共享池） |
| BatchProcessingDemo | integration | 演示批量数据处理与结果分析 |

## 架构验证

### 自动验证（测试）

```bash
# 运行架构约束测试
mvn test -Dtest=ArchitectureConstraintsTest
```

### 手动验证

```bash
# 检查依赖
mvn dependency:tree

# 编译验证（确保不访问内部包）
mvn clean compile

# 检查包结构
find src/main/java -name "*.java" -exec grep -l "import io.github.huatalk.parallelinscope" {} \;
```

## 常见错误

### ❌ 错误示例

```java
// 错误 1: 访问内部包
import io.github.huatalk.parallelinscope.internal.ConcurrentLimitExecutor;

// 错误 2: 使用主项目包名
package io.github.huatalk.parallelinscope.demo;  // 应该是 demo.basic

// 错误 3: 依赖源代码
// pom.xml 中使用 systemPath 指向主项目
```

### ✅ 正确示例

```java
// 正确 1: 只访问公共 API
import io.github.huatalk.parallelinscope.scope.Par;
import io.github.huatalk.parallelinscope.scope.ParOptions;

// 正确 2: 使用独立包名
package demo.basic;

// 正确 3: 依赖发布版本
// pom.xml 中使用 Maven Central 坐标
```

## 添加新示例

1. 选择合适的包（basic/advanced/integration）
2. 创建新的 Java 类，使用 `demo.*` 包名
3. 只导入 `io.github.huatalk.parallelinscope.scope` 和 `io.github.huatalk.parallelinscope.spi` 包
4. 添加对应的测试类
5. 更新 `scripts/run-demos.sh` 脚本

## 维护指南

1. **更新依赖**: 修改 `pom.xml` 中的 `parallel-in-scope.version`
2. **架构验证**: 定期运行 `mvn test -Dtest=ArchitectureConstraintsTest`
3. **检查依赖**: 运行 `mvn dependency:tree` 确保只依赖发布版本

## 注意事项

- Java 版本：8+
- 依赖 parallel-in-scope 发布版本（Maven Central）
- 所有 Demo 都是可独立运行的 main 方法
- 不能访问主项目的内部实现
- 使用独立的包命名空间
