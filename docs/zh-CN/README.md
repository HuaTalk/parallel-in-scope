**中文** | [**English**](../en/README.md)

# 中文文档中心

根 README 负责项目概览；这里是面向使用者和维护者的完整文档导航。

## 使用指南

| 文档 | 定位 |
|---|---|
| [完整使用指南](user-guide.md) | **主文档**：配置、公共 API、执行语义和进阶能力 |
| [5 分钟快速上手](https://github.com/HuaTalk/parallel-in-scope/blob/main/demo/docs/zh-CN/articles/QUICK-START-5-minutes.md) | **用户指南**：用最小示例完成首次调用 |
| [批量调用最佳实践](https://github.com/HuaTalk/parallel-in-scope/blob/main/demo/docs/zh-CN/articles/BATCH-best-practices.md) | **用户指南**：HTTP、数据库和混合 IO 场景 |
| [Demo 文档索引](https://github.com/HuaTalk/parallel-in-scope/blob/main/demo/docs/zh-CN/README.md) | 示例：问题复现、解决方案和可执行测试 |

## API 与契约参考

| 文档 | 定位 |
|---|---|
| [协作式取消](reference/cooperative-cancellation.md) | **API/契约参考**：checkpoint、异常与取消传播 |
| [Nullability Annotations](reference/nullability-annotations.md) | **API/契约参考**：空值注解规则和依赖约定 |

## 实现原理与设计决策

| 文档 | 定位 |
|---|---|
| [ThreadRelay 内部机制](internals/thread-relay.md) | **实现原理**：跨线程上下文传播 |
| [并发库的减法哲学](design/philosophy.md) | **设计决策**：核心取舍与边界 |
| [Idea Graveyard](design/idea-graveyard.md) | **设计决策**：明确不提供的能力及替代方案 |
| [架构与数据链路](visuals/parallel-in-scope-architecture.html) | **实现原理**：执行、取消、调度和检测链路 |
| [交互式用户指南](visuals/parallel-in-scope-user-guide.html) | **用户指南**：主要使用路径与 FAQ |

## 测试与案例

| 文档 | 定位 |
|---|---|
| [AsyncBatchResult 测试设计](testing/async-batch-result-report-test-design.md) | **测试策略**：稳定契约与并发断言 |
| [AI 并发审查误报复盘](case-studies/ai-concurrency-review-false-positive.md) | **案例复盘**：用最小测试验证并发语义 |

## 阅读原则

- “主文档 / API/契约参考”定义当前公共行为。
- Demo 文章解释问题和用法，不替代公共契约。
- 内部原理描述实现，可随版本演进。
