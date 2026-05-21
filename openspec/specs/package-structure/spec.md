## ADDED Requirements

### Requirement: Package layout
The project SHALL organize all classes into the following 7 packages under the base `io.github.huatalk.parallelinscope`:
- `scope` — API facade classes
- `context` — TTL/TL context propagation classes
- `context.graph` — Livelock detection classes
- `cancel` — Cancellation subsystem classes
- `internal` — Execution engine and utility classes
- `queue` — Scheduling queue classes
- `spi` — Extension point interfaces

#### Scenario: All classes are in their designated packages
- **WHEN** the project compiles successfully
- **THEN** each class resides in its assigned package as defined by the class-to-package mapping

### Requirement: Class-to-package mapping
The system SHALL place classes into packages as follows:

**scope:** ParallelHelper, ParallelOptions, AsyncBatchResult, TaskType, Par
**context:** ThreadRelay, TaskScopeTl
**context.graph:** TaskGraph, TaskEdge, TaskEdgeEntry
**cancel:** CancellationToken, CancellationTokenState, Checkpoints, FatCancellationException, LeanCancellationException, PurgeService
**internal:** ConcurrentLimitExecutor, ScopedCallable, Attachable, FutureInspector, ListeningExecutorAdapter
**queue:** SmartBlockingQueue, VariableLinkedBlockingQueue
**spi:** TaskListener, ExecutorResolver, LivelockListener, ParallelLogger

#### Scenario: All framework classes use the canonical package prefix
- **WHEN** the project is inspected
- **THEN** every framework `.java` file SHALL have a package declaration that starts with `io.github.huatalk.parallelinscope`

### Requirement: All classes are public
All classes and interfaces SHALL have `public` visibility after the refactoring.

#### Scenario: Previously package-private classes become public
- **WHEN** classes ScopedCallable, ThreadRelay, Attachable, TaskGraph, TaskEdge, TaskEdgeEntry are moved to new packages
- **THEN** each SHALL have the `public` access modifier

### Requirement: Maven coordinates updated
The Maven artifactId SHALL be `parallel-in-scope` and the project name SHALL be `parallel-in-scope`.

#### Scenario: pom.xml reflects new identity
- **WHEN** the pom.xml is read
- **THEN** the artifactId SHALL be `parallel-in-scope`

### Requirement: Canonical source tree
The framework source tree SHALL use the canonical `io.github.huatalk.parallelinscope` package prefix in both main and test sources.

#### Scenario: Canonical directories exist
- **WHEN** the source tree is inspected
- **THEN** the directory `src/main/java/io/github/huatalk/parallelinscope/` SHALL exist
- **AND** the directory `src/test/java/io/github/huatalk/parallelinscope/` SHALL exist

### Requirement: Tests updated
All test files SHALL update their package declarations and imports to reference the new package structure, and all tests SHALL pass after the refactoring.

#### Scenario: All tests pass
- **WHEN** `mvn test` is executed after refactoring
- **THEN** all existing tests SHALL pass with zero failures
