# Bounded Task Failure Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the concrete sanitized exception chain and bounded stack trace for failed tasks in `FTASK_REASON VARCHAR(4000)` and render its original line breaks on the task detail page.

**Architecture:** Add one package-private diagnostic formatter in the Spark module and extend the existing Service formatter with the same storage contract. Spark and Service failure writers persist only formatter output, while the original exception still propagates to the runtime/logging path; the React task page treats the stored text as escaped plain text and preserves its line breaks through CSS.

**Tech Stack:** Java 17, Apache Spark 3.5, JDBC/H2, Spring Boot, React 18, TypeScript, Vitest/Testing Library, Maven, Bash/ZIP.

**Spec:** `/Users/java/obsidian-worktrees/bd-task-table-new/01 Engineering/bd/数据库比对管理台-系统设计.md`; `/Users/java/obsidian-worktrees/bd-task-table-new/01 Engineering/bd/数据库比对管理台-数据模型.md`; `/Users/java/obsidian-worktrees/bd-task-table-new/01 Engineering/bd/数据库比对管理台-API接口.md`

## Global Constraints

- Reuse `TSS_BCOMP_TASK_NEW.FTASK_REASON VARCHAR(4000)`; do not add or alter production tables or columns.
- Persist exception type, sanitized message, cause chain, and bounded stack frames with original line breaks.
- The stored Java string must never exceed 4000 UTF-16 code units; truncated output must end with `…[已截断]`.
- Sanitize JDBC URLs, URI-embedded credentials, and assignments whose names contain `password`, `passwd`, `pwd`, `passphrase`, `secret`, or `token`.
- Never include the Spark 21 launch arguments, plaintext database passwords, or the Jasypt encryptor password in persisted diagnostics, tests, logs, ZIP listings, or command output.
- Preserve the original exception propagation and Spark final state; failure-diagnostic generation or persistence must not replace the original error.
- Generic `ApiError` responses remain stack-free. Only persisted task `failureReason` exposes the sanitized diagnostic in the task-detail operations context.
- Preserve existing historical `FTASK_REASON` values; only failures created after deployment use the new format.
- Build and test with JDK 17 from `/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home`.
- Rebuild the frontend, embed it under Service static resources, and deliver the current Service/Spark sources plus encrypted `config/bd.env` in the source ZIP.

## Review Focus

- A diagnostic exactly at and beyond the 4000-character boundary must remain valid and use the truncation marker only when content was removed.
- A cyclic or repeated cause chain must terminate without hanging or duplicating causes indefinitely.
- Null messages, empty stack traces, and hostile `Throwable` accessors must still produce a useful exception-class fallback.
- Secrets embedded in a nested cause, JDBC URL, URI authority (username and password), or mixed-case assignment name must be redacted before truncation.
- If failure-state persistence itself fails, the original comparison exception must remain the exception propagated by Spark, with the persistence error only suppressed.

---

### Task 1: Spark storage-safe failure formatter

**Files:**
- Create: `compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/SparkFailureDiagnostics.java`
- Create: `compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/SparkFailureDiagnosticsTest.java`

**Interfaces:**
- Produces: `static String SparkFailureDiagnostics.storage(Throwable error)` returning sanitized multiline text of at most 4000 Java characters.
- Consumes: no application state or Spark arguments; only the supplied throwable and its cause/stack graph.

- [ ] **Step 1: Write failing formatter tests**

Create `SparkFailureDiagnosticsTest` with these tests:

```java
@Test void storesSanitizedCauseChainAndStackFramesWithinFourThousandCharacters()
@Test void preservesExactlyFourThousandCharactersWithoutTruncation()
@Test void truncatesBeyondFourThousandCharactersWithAnExplicitMarker()
@Test void terminatesCyclicCausesAndHandlesNullMessages()
@Test void fallsBackWhenThrowableAccessorsFail()
```

Assert that the first test contains the top-level and nested exception class/message, `Caused by:`, a frame naming `SparkFailureDiagnosticsTest`, and newline characters. Put credential material in mixed-case password/token assignments, a JDBC URL, and a URI authority, then assert only `[REDACTED]`/`jdbc:[REDACTED]` remain and neither the URI username nor password survives. Assert every result is at most 4000 characters.

For the exact-boundary test, use an empty-stack throwable and size its message from the formatter's class-name prefix so the result is exactly 4000 characters; assert it does not end with `…[已截断]`. For the over-boundary test, use a message longer than 6000 characters and assert the result is exactly 4000 characters ending with `…[已截断]`. For the cycle test, use a test throwable whose `getCause()` returns itself and assert formatting returns once with its class name. For the fallback test, use a throwable whose message, cause, or stack accessor throws and assert formatting still returns a sanitized class-name diagnostic within the limit.

- [ ] **Step 2: Run the formatter test and verify RED**

Run:

```bash
export JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
mvn -P'!bes' -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true -pl compare-spark -Dtest=SparkFailureDiagnosticsTest test
```

Expected: FAIL because `SparkFailureDiagnostics` does not exist.

- [ ] **Step 3: Implement the Spark formatter**

Create package-private `final class SparkFailureDiagnostics` with:

```java
static String storage(Throwable error)
```

Use identity-based cause-cycle detection, at most 8 causes, and at most 12 stack frames per cause. Format the first cause as `Type: message`, later causes as `Caused by: Type: message`, and frames as `\n  at ...`. Apply secret/JDBC/URI-authority sanitization to messages before appending. Bound the final string once, reserving space for the exact truncation marker; a null throwable returns `UnknownFailure`. Catch formatter-internal failures and fall back to the safely derived exception class name so diagnostic generation cannot block failure-state handling.

- [ ] **Step 4: Run the formatter test and verify GREEN**

Run the Step 2 command again.

Expected: all `SparkFailureDiagnosticsTest` tests PASS.

- [ ] **Step 5: Commit the formatter slice**

```bash
git add compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/SparkFailureDiagnostics.java compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/SparkFailureDiagnosticsTest.java
git commit -m "feat: format bounded Spark failure diagnostics"
```

### Task 2: Persist Spark failure detail without masking the original error

**Files:**
- Modify: `compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/SparkHandlerTable.java:187-194`
- Create: `compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/SparkFailurePersistenceTest.java`

**Interfaces:**
- Consumes: `SparkFailureDiagnostics.storage(Throwable)` from Task 1.
- Produces: `static void SparkHandlerTable.recordFailure(String[] args, Throwable error) throws Exception`, which writes state `4`, result `3`, the bounded diagnostic, and end time for the exact running task.
- Produces: `static void SparkHandlerTable.rethrowAfterRecordingFailure(String[] args, Exception error) throws Exception`, which always rethrows the same `error` instance and attaches any persistence failure as suppressed.

- [ ] **Step 1: Write failing persistence tests**

Create `SparkFailurePersistenceTest` with H2 and `sql/create_bcomp_new.sql`:

```java
@Test void recordsConcreteSanitizedFailureAndStackWithinVarcharLimit()
@Test void leavesNonRunningOrDifferentIdentityRowsUntouched()
@Test void rethrowsOriginalFailureWhenFailurePersistenceAlsoFails()
```

Build a 21-element argument array with H2 metadata connection values and insert the matching state-`2` task. Pass a nested `SQLException` containing safe cause text plus credential fixtures to `recordFailure`. Assert `TASK_STATE='4'`, `TASK_RESULT='3'`, `FTASK_REASON` contains the concrete cause and test stack frame, excludes every fixture secret/JDBC host, contains newlines, and has length at most 4000. The second test proves the update predicate still requires all four identity values and state `2`. For the third test, make the metadata connection fail, call `rethrowAfterRecordingFailure`, and assert by identity that the caught exception is the original comparison exception and that the write exception appears only in `getSuppressed()`.

- [ ] **Step 2: Run the persistence test and verify RED**

Run:

```bash
export JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
mvn -P'!bes' -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true -pl compare-spark -Dtest=SparkFailurePersistenceTest test
```

Expected: FAIL because `recordFailure` and `rethrowAfterRecordingFailure` do not exist and the catch block still stores the fixed message.

- [ ] **Step 3: Extract and wire `recordFailure`**

Move only the existing failure-update JDBC operation into:

```java
static void recordFailure(String[] args, Throwable error) throws Exception
```

Bind `SparkFailureDiagnostics.storage(error)` as `FTASK_REASON`. Add and use from the existing catch block:

```java
static void rethrowAfterRecordingFailure(String[] args, Exception error) throws Exception
```

It calls `recordFailure`, adds any write error as suppressed to `error`, and always throws the same original instance. Do not log or persist the argument array.

- [ ] **Step 4: Run Spark failure tests and verify GREEN**

Run:

```bash
mvn -P'!bes' -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true -pl compare-spark -Dtest=SparkFailureDiagnosticsTest,SparkFailurePersistenceTest test
```

Expected: all formatter and persistence tests PASS.

- [ ] **Step 5: Commit the Spark persistence slice**

```bash
git add compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/SparkHandlerTable.java compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/SparkFailurePersistenceTest.java
git commit -m "feat: persist concrete Spark failure diagnostics"
```

### Task 3: Persist Service-side exception stacks under the same contract

**Files:**
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/SafeDiagnostics.java`
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/TaskWorker.java:64-70`
- Modify: `compare-service/src/test/java/cn/sunline/tss/compare/SafeDiagnosticsTest.java`
- Modify: `compare-service/src/test/java/cn/sunline/tss/compare/TaskWorkerTest.java`
- Modify: `compare-service/src/test/resources/legacy-schema.sql:3`

**Interfaces:**
- Produces: `static String SafeDiagnostics.storageDetail(Throwable error)` with the same 4000-character sanitized multiline contract as the Spark formatter.
- Consumes: `LegacyRepository.fail(Task task, String reason)` unchanged; the caller guarantees the stored value fits the column.

- [ ] **Step 1: Write failing Service formatter and worker tests**

Extend `SafeDiagnosticsTest` with:

```java
@Test void storageDetailPreservesSanitizedCauseAndStackWithinDatabaseLimit()
@Test void storageDetailUsesExplicitTruncationMarker()
```

Assert the same cause, stack, redaction, newline, 4000-character, and truncation behavior as Task 1 while preserving existing `summary`, `message`, and `logDetail` behavior.

Update `failedSubmissionIsRecordedWithoutKillingWorker` so the thrown exception has a nested cause and a message longer than 4000 characters. Assert the stored `FTASK_REASON` contains both exception types, `TaskWorkerTest`, and newlines; excludes fixture secrets; ends with the truncation marker when truncated; and has length at most 4000.

- [ ] **Step 2: Run focused Service tests and verify RED**

Run:

```bash
export JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
mvn -P'!bes',local-servlet-tests -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true -pl compare-service -Dtest=SafeDiagnosticsTest,TaskWorkerTest test
```

Expected: FAIL because `storageDetail` does not exist and `TaskWorker` persists only the 500-character summary; the test schema may also reject values beyond its obsolete 2000-character test width.

- [ ] **Step 3: Implement Service storage formatting and wiring**

Add:

```java
static String storageDetail(Throwable error)
```

Refactor only private formatter helpers needed to share sanitization, cycle detection, cause/frame limits, and the exact truncation marker with `logDetail`; keep public/package-visible existing behavior stable. Change `TaskWorker`'s exception path to call `storageDetail(e)` for `repository.fail`. Keep `logDetail(e)` for the service log. Update only the test schema's `FTASK_REASON` width from 2000 to 4000; production DDL already uses 4000 and needs no migration.

- [ ] **Step 4: Run focused Service tests and verify GREEN**

Run the Step 2 command again.

Expected: all `SafeDiagnosticsTest` and `TaskWorkerTest` tests PASS.

- [ ] **Step 5: Commit the Service persistence slice**

```bash
git add compare-service/src/main/java/cn/sunline/tss/compare/SafeDiagnostics.java compare-service/src/main/java/cn/sunline/tss/compare/TaskWorker.java compare-service/src/test/java/cn/sunline/tss/compare/SafeDiagnosticsTest.java compare-service/src/test/java/cn/sunline/tss/compare/TaskWorkerTest.java compare-service/src/test/resources/legacy-schema.sql
git commit -m "feat: persist bounded service failure stacks"
```

### Task 4: Preserve diagnostic formatting on the task detail page

**Files:**
- Modify: `compare-ui/src/pages/TaskDetailPage.tsx:22-41`
- Modify: `compare-ui/src/pages/TaskDetailPage.test.tsx`
- Modify: `compare-ui/src/styles/layout.css:44`

**Interfaces:**
- Consumes: `TaskSummary.failureReason` as escaped plain text containing optional newlines and a maximum of 4000 characters.
- Produces: `.failure-diagnostic` presentation with preserved whitespace, a monospace font, and long-token wrapping in both daily-result and legacy execution-detail branches.

- [ ] **Step 1: Write failing UI tests**

Update the failed-task test to return:

```text
PSQLException: duplicate key\n  at SparkHandlerTable.recordFailure(SparkHandlerTable.java:190)\nCaused by: SQLException: constraint
```

Assert the execution-panel failure element has class `failure-diagnostic` and its `textContent` exactly preserves the newline sequence. Add a second test with a non-null `resultDate` proving the early daily-result branch applies the same class and preserves text.

- [ ] **Step 2: Run the UI test and verify RED**

Run:

```bash
cd compare-ui
npm test -- --run src/pages/TaskDetailPage.test.tsx
```

Expected: FAIL because neither rendering branch applies the diagnostic presentation class.

- [ ] **Step 3: Add escaped multiline presentation**

Apply `failure-diagnostic` to the existing failure `<dd>` and daily-result error notice; keep React text interpolation unchanged. Add CSS with monospace font, `white-space: pre-wrap`, `overflow-wrap: anywhere`, and readable line height. Do not use `dangerouslySetInnerHTML` or parse stack contents.

- [ ] **Step 4: Run the focused UI test and verify GREEN**

Run the Step 2 command again.

Expected: all `TaskDetailPage.test.tsx` tests PASS.

- [ ] **Step 5: Commit the UI slice**

```bash
git add compare-ui/src/pages/TaskDetailPage.tsx compare-ui/src/pages/TaskDetailPage.test.tsx compare-ui/src/styles/layout.css
git commit -m "feat: show multiline task failure diagnostics"
```

### Task 5: Full verification and refreshed source delivery

**Files:**
- Generated: `compare-ui/dist/**`
- Generated: `target/tss-compare-source-1.0.0-SNAPSHOT.zip`
- Generated: `target/tss-compare-source-1.0.0-SNAPSHOT.zip.sha256`
- Inject from existing deployment overlay: `target/bd-postgresql-overlay-20260923/config/bd.env`

**Interfaces:**
- Consumes: all committed Java/UI changes from Tasks 1-4 and the existing encrypted deployment configuration.
- Produces: a clean, reproducible source ZIP whose `SOURCE-COMMIT.txt` equals HEAD and whose embedded frontend shows multiline diagnostics.

- [ ] **Step 1: Run the complete backend suite**

Run:

```bash
export JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
mvn -P'!bes',local-servlet-tests -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true test
```

Expected: Service and Spark reactor modules PASS with zero failures; the explicitly environment-gated real database test may remain skipped.

- [ ] **Step 2: Run all frontend tests and build production assets**

Run:

```bash
cd compare-ui
npm test -- --run
npm run build
cd ..
```

Expected: all Vitest files PASS and Vite production build succeeds.

- [ ] **Step 3: Verify the tracked worktree and package current HEAD**

Run `git diff --check` and `git status --short`; expected tracked worktree is clean. Then run:

```bash
./bin/package-source.sh
```

Expected: the source ZIP and SHA-256 file are regenerated from current HEAD with frontend assets embedded under `compare-service/src/main/resources/static`.

- [ ] **Step 4: Inject encrypted `bd.env` and refresh SHA-256**

Use a temporary directory, preserve the ZIP root `tss-compare-source-1.0.0-SNAPSHOT/config/bd.env`, update the archive with `zip -u`, and regenerate the checksum. Do not display configuration values or delete broad paths.

- [ ] **Step 5: Verify delivery contents and secret policy**

Verify all of the following with exit-code checks:

- `unzip -t` and `shasum -a 256 -c` pass.
- `SOURCE-COMMIT.txt` equals `git rev-parse HEAD`.
- both Maven modules, embedded frontend, `SparkFailureDiagnostics.java`, its tests, `SafeDiagnostics.storageDetail`, persistence tests, and UI multiline tests are present.
- packaged `config/bd.env` contains an `ENC(...)` metadata password and no `JASYPT_ENCRYPTOR_PASSWORD` assignment.
- an octal-constructed scan finds neither the known plaintext database password, its obsolete escaped form, nor the Jasypt passphrase assignment anywhere in the extracted package.
- the worktree remains clean and the branch is not merged or pushed unless the user separately requests it.

Expected: every delivery check prints PASS without printing credentials.
