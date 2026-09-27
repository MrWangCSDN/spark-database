# Field Pattern Overflow Top127 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make comparisons with more than 128 exact field-difference combinations complete successfully by publishing the exact Top127 combinations plus one exact `__OTHER__` summary row.

**Architecture:** Cache the primary-key `cogroup` RDD with serialized memory-and-disk storage and run two exact aggregation passes over that cache. The first pass counts every field pattern and deterministically selects Top127 by count descending then pattern ID ascending; the second pass reuses the same comparison function, retains samples only for those IDs, and injects an exact sample-free `__OTHER__` statistic before transactional publication. Service and UI expose the collapsed count while preventing `__OTHER__` from becoming a sample filter.

**Tech Stack:** Java 17, Apache Spark RDD API, Spring Boot/JDBC, H2/PostgreSQL-compatible DDL, React 18, TypeScript, TanStack Query, Vitest/Testing Library, Maven, Bash/ZIP.

**Spec:** `/Users/java/obsidian-worktrees/bd-task-table-new/01 Engineering/bd/每日核对结果-系统设计.md`; `/Users/java/obsidian-worktrees/bd-task-table-new/01 Engineering/bd/每日核对结果-数据模型.md`; `/Users/java/obsidian-worktrees/bd-task-table-new/01 Engineering/bd/每日核对结果-API接口.md`

## Global Constraints

- Keep exactly 127 ordinary patterns when more than 127 exist; publish all remaining patterns as one reserved `PATTERN_ID='__OTHER__'` row.
- Rank ordinary patterns by `DIFFERENT_COUNT DESC, PATTERN_ID ASC`; `__OTHER__` is always last.
- `__OTHER__` has `FIELDS_JSON='[]'`, `SAMPLE_COUNT=0`, exact `COLLAPSED_PATTERN_COUNT`, and exact summed `DIFFERENT_COUNT`; it never has detail rows.
- The sum of all published pattern `DIFFERENT_COUNT` values must equal the daily result `DIFFERENT_COUNT`, or publication must fail and roll back.
- The field-pattern sample budget remains 20,000 total. For a positive configured detail limit, the retained-pattern limit is `max(1, min(detailLimit, floor(20000 / retainedPatternCount)))`; configured limit `0` continues to disable all samples.
- Cache the primary-key `cogroup` with serialized memory-and-disk storage, reuse it for both passes, and unpersist it on success and failure; do not reread either source database.
- Use one row-comparison implementation in both passes, including duplicate-key validation, ignore rules, enum conversion, numeric handling, and field-order identity.
- Add `COLLAPSED_PATTERN_COUNT INT NOT NULL DEFAULT 1 CHECK (COLLAPSED_PATTERN_COUNT > 0)` to new and migrated schemas.
- Do not put plaintext database passwords or the Jasypt encryptor password in source, logs, tests, ZIP listings, or command output.
- Build and test with JDK 17 from `/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home`.
- Deliver both Maven modules, the built frontend embedded under Service static resources, the new migration SQL, and the configured encrypted `bd.env` in one source ZIP.

## Review Focus

- Exactly 127 patterns must not produce `__OTHER__`; 128 patterns must retain 127 and collapse exactly one.
- Equal-count patterns must be selected reproducibly by pattern ID, independent of partition/merge order.
- Counts near `Long.MAX_VALUE` must use exact arithmetic and fail on overflow rather than wrap.
- A configured detail limit of zero must collect no samples while keeping all exact counts.
- Any first-pass or second-pass exception, including duplicate business keys, must release the cached `cogroup` and preserve the existing task-failure path.

---

### Task 1: Schema migration for collapsed pattern counts

**Files:**
- Create: `sql/add_bcomp_pattern_overflow_new.sql`
- Modify: `sql/create_bcomp_new.sql`
- Modify: `compare-service/src/test/resources/legacy-schema.sql`
- Modify: `compare-service/src/test/java/cn/sunline/tss/compare/NewSchemaTest.java`

**Interfaces:**
- Produces: `TSS_BCOMP_FIELD_DIFF_NEW.COLLAPSED_PATTERN_COUNT INT NOT NULL DEFAULT 1` with a positive-value check in fresh and existing installations.
- Consumes: the existing `TSS_BCOMP_FIELD_DIFF_NEW` table created by `sql/add_bcomp_field_patterns_new.sql`.

- [ ] **Step 1: Write the failing schema tests**

Add assertions to `productionDdlCreatesEmptyTablesSupportsWideSqlAndUniqueTaskIdentity` proving a normal inserted pattern defaults to `1` and rejects `0`. Add this migration test:

```java
@Test void patternOverflowMigrationDefaultsExistingRowsAndRejectsNonPositiveCounts() {
    var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new FileSystemResource("../sql/create_bcomp_new.sql")).execute(ds);
    var jdbc = new JdbcTemplate(ds);
    jdbc.update("ALTER TABLE TSS_BCOMP_FIELD_DIFF_NEW DROP COLUMN COLLAPSED_PATTERN_COUNT");
    jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT) VALUES(1,'20260927','run','" + "a".repeat(64) + "','[\"a\"]',3,1)");
    new ResourceDatabasePopulator(new FileSystemResource("../sql/add_bcomp_pattern_overflow_new.sql")).execute(ds);
    assertThat(jdbc.queryForObject("SELECT COLLAPSED_PATTERN_COUNT FROM TSS_BCOMP_FIELD_DIFF_NEW", Integer.class)).isEqualTo(1);
    assertThatThrownBy(() -> jdbc.update("UPDATE TSS_BCOMP_FIELD_DIFF_NEW SET COLLAPSED_PATTERN_COUNT=0"))
        .isInstanceOf(DataIntegrityViolationException.class);
}
```

- [ ] **Step 2: Run the schema test and verify RED**

Run:

```bash
export JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
mvn -P'!bes',local-servlet-tests -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true -pl compare-service -Dtest=NewSchemaTest test
```

Expected: FAIL because the column and `sql/add_bcomp_pattern_overflow_new.sql` do not exist.

- [ ] **Step 3: Add the production and test DDL**

Add this column inside both complete schema definitions:

```sql
COLLAPSED_PATTERN_COUNT INT NOT NULL DEFAULT 1 CHECK (COLLAPSED_PATTERN_COUNT > 0),
```

Create the idempotence-by-deployment-contract migration:

```sql
-- Existing bd schema: execute once after stopping bd and all in-flight Spark jobs.
BEGIN;
ALTER TABLE TSS_BCOMP_FIELD_DIFF_NEW
    ADD COLUMN COLLAPSED_PATTERN_COUNT INT NOT NULL DEFAULT 1;
ALTER TABLE TSS_BCOMP_FIELD_DIFF_NEW
    ADD CONSTRAINT chk_bcomp_field_diff_collapsed_count
    CHECK (COLLAPSED_PATTERN_COUNT > 0);
COMMIT;
```

- [ ] **Step 4: Run the focused schema test and verify GREEN**

Run the Step 2 command again.

Expected: PASS, including preservation/defaulting of an existing pattern row.

- [ ] **Step 5: Commit the schema slice**

```bash
git add sql/add_bcomp_pattern_overflow_new.sql sql/create_bcomp_new.sql compare-service/src/test/resources/legacy-schema.sql compare-service/src/test/java/cn/sunline/tss/compare/NewSchemaTest.java
git commit -m "feat: add collapsed field pattern schema"
```

### Task 2: Exact pattern counting and deterministic Top127 selection

**Files:**
- Create: `compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/PatternCounts.java`
- Create: `compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/PatternCountsTest.java`
- Modify: `compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/ResultAggregate.java`
- Modify: `compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/FieldPatternResultTest.java`

**Interfaces:**
- Produces: `PatternCounts.add(List<String>): PatternCounts`, package-private `PatternCounts.add(FieldPattern, long): PatternCounts`, `PatternCounts.merge(PatternCounts): PatternCounts`, `PatternCounts.snapshot(): Map<String,Long>`, and `PatternCounts.select(int retainedLimit, int configuredSampleLimit): Selection`.
- Produces: `Selection.retainedIds(): Set<String>`, `Selection.collapsedPatternCount(): int`, `Selection.collapsedDifferentCount(): long`, and `Selection.patternSampleLimit(): int`.
- Produces: `ResultAggregate(int detailLimit, Set<String> retainedPatternIds, int patternSampleLimit)` and `ResultAggregate.collapse(int patternCount, long differentCount)`; its existing one-argument constructor remains usable for non-overflow unit tests.

- [ ] **Step 1: Write failing exact-selection tests**

Create `PatternCountsTest` with tests equivalent to:

```java
@Test void keeps127AndCollapsesTheRestWithExactCounts() {
    var counts=new PatternCounts();
    for(int i=0;i<130;i++) for(int n=0;n<=i;n++) counts.add(List.of("f"+i));
    var selection=counts.select(127,1000);
    assertEquals(127,selection.retainedIds().size());
    assertEquals(3,selection.collapsedPatternCount());
    assertEquals(1L+2L+3L,selection.collapsedDifferentCount());
    assertEquals(157,selection.patternSampleLimit());
}

@Test void usesPatternIdAsStableTieBreakerAndDoesNotCollapse127() {
    var counts=new PatternCounts();
    var ids=new ArrayList<String>();
    for(int i=0;i<128;i++) { var fields=List.of("same-count-"+i); counts.add(fields); ids.add(new FieldPattern(fields).id); }
    var selection=counts.select(127,1000);
    ids.sort(String::compareTo);
    assertEquals(new LinkedHashSet<>(ids.subList(0,127)),selection.retainedIds());
    var exact=new PatternCounts();
    for(int i=0;i<127;i++) exact.add(List.of("f"+i));
    assertEquals(0,exact.select(127,1000).collapsedPatternCount());
}

@Test void mergeIsOrderIndependentUsesExactArithmeticAndHonorsZeroSampleLimit() {
    var leftThenRight=new PatternCounts().add(List.of("a")).merge(new PatternCounts().add(List.of("b")));
    var rightThenLeft=new PatternCounts().add(List.of("b")).merge(new PatternCounts().add(List.of("a")));
    assertEquals(leftThenRight.snapshot(),rightThenLeft.snapshot());
    assertEquals(0,leftThenRight.select(127,0).patternSampleLimit());
    var overflow=new PatternCounts();
    var pattern=new FieldPattern(List.of("overflow"));
    overflow.add(pattern,Long.MAX_VALUE);
    assertThrows(ArithmeticException.class,() -> overflow.add(pattern,1));
}
```

Replace the former overflow-exception test in `FieldPatternResultTest` with a test that builds 128 combinations, configures 127 retained IDs, verifies the aggregate does not throw, retains only those patterns, and accounts for the injected collapsed row.

- [ ] **Step 2: Run Spark unit tests and verify RED**

Run:

```bash
export JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
mvn -P'!bes' -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true -pl compare-spark -Dtest=PatternCountsTest,FieldPatternResultTest test
```

Expected: FAIL because `PatternCounts`, `Selection`, and the retained-ID aggregate constructor do not exist.

- [ ] **Step 3: Implement exact counts and selection**

Implement `PatternCounts` as a serializable map keyed by `FieldPattern.id`. Each entry stores the canonical JSON and a `long` count; `add(List<String>)` delegates to the package-private weighted `add(FieldPattern,long)` used by merge/tests. Reject non-positive deltas and the same ID with different JSON, use `Math.addExact`, expose an immutable ID-to-count `snapshot`, and sort entries with:

```java
Comparator.comparingLong(PatternCount::count).reversed()
    .thenComparing(PatternCount::id)
```

Return retained IDs in ranking order. Compute collapsed pattern count and collapsed row count from all entries after index 126. Compute the positive detail sample limit with:

```java
configuredSampleLimit == 0 || retainedIds.isEmpty()
    ? 0
    : Math.max(1,Math.min(configuredSampleLimit,ResultAggregate.MAX_PATTERN_SAMPLES/retainedIds.size()))
```

- [ ] **Step 4: Make `ResultAggregate` retain samples only for selected IDs**

Remove the `MAX_PATTERNS` exception. Count every type-4 row in `different`, but create/update `PatternStats` only when the pattern ID is retained. Give type-2/type-3/general preview samples the configured `detailLimit`, and give per-pattern maps the separate `patternSampleLimit`. Represent the collapsed result as one immutable `PatternStats` with ID `__OTHER__`, JSON `[]`, count from the first pass, zero samples, and a positive collapsed-pattern count.

Ensure:

```java
long patternCount()
```

sums retained exact counts plus the collapsed exact count, and:

```java
List<PatternStats> patterns()
```

returns ordinary retained patterns followed by `__OTHER__` when present.

- [ ] **Step 5: Run focused Spark tests and verify GREEN**

Run the Step 2 command again.

Expected: PASS; 128+ combinations no longer throw, selection is stable, and total counts remain exact.

- [ ] **Step 6: Commit the exact aggregation slice**

```bash
git add compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/PatternCounts.java compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/ResultAggregate.java compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/PatternCountsTest.java compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/FieldPatternResultTest.java
git commit -m "feat: select exact top field patterns"
```

### Task 3: Two-pass Spark comparison over one cached input

**Files:**
- Modify: `compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/SparkHandlerTable.java`
- Modify: `compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/ComparisonSummaryTest.java`
- Create: `compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/PatternOverflowPipelineTest.java`

**Interfaces:**
- Consumes: `PatternCounts.Selection` and retained-ID `ResultAggregate` from Task 2.
- Produces: `SparkHandlerTable.countPatternsPartition(...)` and `SparkHandlerTable.comparePartition(..., int detailLimit, Set<String> retainedPatternIds, int patternSampleLimit)`.
- Produces: one cached `JavaPairRDD<String,Tuple2<Iterable<String[]>,Iterable<String[]>>>` reused by the count and sample passes.

- [ ] **Step 1: Write failing shared-comparison and overflow pipeline tests**

Extend `ComparisonSummaryTest` so the same group set sent through `countPatternsPartition` and retained `comparePartition` produces identical type-4 counts/IDs, including ignored fields and enum/numeric comparison. Create a local-Spark test with 130 distinct combinations and shuffled partition order that asserts:

```java
assertEquals(127,result.patterns().stream().filter(p -> !"__OTHER__".equals(p.id)).count());
assertEquals(1,result.patterns().stream().filter(p -> "__OTHER__".equals(p.id)).count());
assertEquals(130,result.patternCount());
assertEquals(result.different,result.patternCount());
assertEquals(0,result.patterns().stream().filter(p -> "__OTHER__".equals(p.id)).findFirst().orElseThrow().sampleCount());
```

Also add an injected failing second-pass test around a package-private two-pass helper and assert its cached RDD reports `StorageLevel.NONE()` after the exception.

- [ ] **Step 2: Run focused Spark tests and verify RED**

Run:

```bash
export JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
mvn -P'!bes' -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true -pl compare-spark -Dtest=ComparisonSummaryTest,PatternOverflowPipelineTest test
```

Expected: FAIL because the count pass, retained compare overload, and cache-lifecycle helper do not exist.

- [ ] **Step 3: Extract one comparison result for both passes**

Add a serializable/package-private comparison value carrying `type`, business key, differing field names, and the original/destination arrays needed for lazy sample creation. Move `uniqueRecord` and `compareRows` invocation into one helper. Both `countPatternsPartition` and `comparePartition` must iterate groups through that helper; only their aggregators differ.

- [ ] **Step 4: Cache once, run two exact actions, and always unpersist**

In `SparkRun`, persist the `cogroup` with:

```java
rsCogroup.persist(org.apache.spark.storage.StorageLevel.MEMORY_AND_DISK_SER());
```

Within `try/finally`, first reduce `PatternCounts`, select Top127, then reduce `ResultAggregate` using the computed retained IDs and sample limit, inject collapsed statistics, validate counts, and publish. The `finally` block must call:

```java
rsCogroup.unpersist(false);
```

Keep the existing outer catch that marks the task failed. Do not call `readSide`, `RddToPair`, or `cogroup` a second time.

- [ ] **Step 5: Run focused and complete Spark tests and verify GREEN**

Run:

```bash
mvn -P'!bes' -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true -pl compare-spark test
```

Expected: PASS, including deterministic Top127 output and cache release on both success and failure.

- [ ] **Step 6: Commit the Spark pipeline slice**

```bash
git add compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/SparkHandlerTable.java compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/ComparisonSummaryTest.java compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/PatternOverflowPipelineTest.java
git commit -m "feat: compare overflow patterns in two exact passes"
```

### Task 4: Transactional publication and Service API contract

**Files:**
- Modify: `compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/DailyResultPublisher.java`
- Modify: `compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/DailyResultPublisherTest.java`
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/result/DailyResultRepository.java`
- Modify: `compare-service/src/test/java/cn/sunline/tss/compare/result/DailyResultApiTest.java`
- Modify: `compare-service/src/test/java/cn/sunline/tss/compare/ApplicationSmokeTest.java`

**Interfaces:**
- Consumes: ordinary and collapsed `ResultAggregate.PatternStats` from Tasks 2-3.
- Produces: `FieldPatternResult(String patternId, List<String> fields, long differentCount, int sampleCount, int collapsedPatternCount)`.
- Produces: `GET /api/results/{index}/{date}/field-patterns` with ordinary rows ordered by count/ID and `__OTHER__` last.

- [ ] **Step 1: Write failing publisher and API tests**

Add a publisher test that publishes 127 ordinary patterns plus a collapsed statistic and asserts:

```java
assertEquals(128,count(c,"SELECT COUNT(*) FROM TSS_BCOMP_FIELD_DIFF_NEW"));
assertEquals(3,count(c,"SELECT COLLAPSED_PATTERN_COUNT FROM TSS_BCOMP_FIELD_DIFF_NEW WHERE PATTERN_ID='__OTHER__'"));
assertEquals(0,count(c,"SELECT SAMPLE_COUNT FROM TSS_BCOMP_FIELD_DIFF_NEW WHERE PATTERN_ID='__OTHER__'"));
assertEquals(0,count(c,"SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW WHERE PATTERN_ID='__OTHER__'"));
assertEquals(count(c,"SELECT DIFFERENT_COUNT FROM TSS_BCOMP_RESULT_NEW"),count(c,"SELECT SUM(DIFFERENT_COUNT) FROM TSS_BCOMP_FIELD_DIFF_NEW"));
```

Add API fixtures for one ordinary row and `__OTHER__`, then assert `collapsedPatternCount` is `1` and `3` respectively, fields for `__OTHER__` are empty, `__OTHER__` sorts last, and `/differences?...patternId=__OTHER__` returns HTTP 400.

- [ ] **Step 2: Run focused backend tests and verify RED**

Run:

```bash
export JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
mvn -P'!bes',local-servlet-tests -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true -Dsurefire.failIfNoSpecifiedTests=false -pl compare-spark,compare-service -Dtest=DailyResultPublisherTest,DailyResultApiTest,ApplicationSmokeTest test
```

Expected: FAIL because publisher INSERT and API records do not include the new column.

- [ ] **Step 3: Publish collapsed counts but never collapsed samples**

Extend the `TSS_BCOMP_FIELD_DIFF_NEW` INSERT column/value list with `COLLAPSED_PATTERN_COUNT`. Bind `1` for ordinary patterns and the exact positive count for `__OTHER__`. Skip `__OTHER__` in the detail-row loop even if a future malformed aggregate exposes samples. Keep the pre-transaction equality check between total pattern counts and `result.different`.

- [ ] **Step 4: Expose the new API field with deterministic ordering**

Select `COLLAPSED_PATTERN_COUNT`, construct the five-field record, and order with:

```sql
ORDER BY CASE WHEN PATTERN_ID='__OTHER__' THEN 1 ELSE 0 END,
         DIFFERENT_COUNT DESC,
         PATTERN_ID ASC
```

Keep the controller's `[a-f0-9]{64}` validation, so `__OTHER__` cannot be requested as a detail filter.

- [ ] **Step 5: Run focused backend tests and verify GREEN**

Run the Step 2 command again.

Expected: PASS; transaction rollback behavior from existing tests remains intact.

- [ ] **Step 6: Commit publication and API changes**

```bash
git add compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/DailyResultPublisher.java compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/DailyResultPublisherTest.java compare-service/src/main/java/cn/sunline/tss/compare/result/DailyResultRepository.java compare-service/src/test/java/cn/sunline/tss/compare/result/DailyResultApiTest.java compare-service/src/test/java/cn/sunline/tss/compare/ApplicationSmokeTest.java
git commit -m "feat: publish collapsed field pattern totals"
```

### Task 5: UI display for the non-selectable other-pattern summary

**Files:**
- Modify: `compare-ui/src/api/types.ts`
- Modify: `compare-ui/src/pages/DailyResultDetailPage.tsx`
- Modify: `compare-ui/src/pages/DailyResultDetailPage.test.tsx`

**Interfaces:**
- Consumes: API `FieldPattern.collapsedPatternCount` from Task 4.
- Produces: a non-button `__OTHER__` summary reading `其余 N 种组合，共 M 条差异，未保存样本`.

- [ ] **Step 1: Write the failing UI test**

Extend the mock type/fixtures with `collapsedPatternCount`. Add:

```tsx
test('shows collapsed patterns last without requesting their samples', async () => {
  const requested: Array<string | null> = []
  mock(false, [
    { patternId: 'balance', fields: ['balance'], differentCount: 90, sampleCount: 3, collapsedPatternCount: 1 },
    { patternId: '__OTHER__', fields: [], differentCount: 40, sampleCount: 0, collapsedPatternCount: 12 }
  ])
  server.use(http.get('/api/results/1/20260920/differences', ({ request }) => {
    requested.push(new URL(request.url).searchParams.get('patternId'))
    return HttpResponse.json({ summary: daily, items: [], page: 0, size: 100, total: 3 })
  }))
  const user=userEvent.setup()
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  await user.click(await screen.findByRole('tab', { name: /主键相同、字段不同/ }))
  expect(await screen.findByText('其余 12 种组合，共 40 条差异，未保存样本')).toBeVisible()
  expect(screen.queryByRole('button', { name: /其余 12 种组合/ })).not.toBeInTheDocument()
  expect(requested).not.toContain('__OTHER__')
})
```

- [ ] **Step 2: Run the UI test and verify RED**

Run:

```bash
cd compare-ui
npm test -- --run src/pages/DailyResultDetailPage.test.tsx
```

Expected: FAIL because the API type and special rendering do not exist and `__OTHER__` is currently selectable.

- [ ] **Step 3: Implement ordinary-pattern selection and other-pattern rendering**

Add `collapsedPatternCount: number` to `FieldPattern`. Derive `selectablePatterns` by excluding `patternId === '__OTHER__'`, choose the default only from that list, render ordinary buttons as today, and render the collapsed row as non-interactive text with the exact approved wording. Keep legacy fallback behavior only for a genuinely empty pattern response; the presence of only `__OTHER__` must not trigger a sample request.

- [ ] **Step 4: Run the focused and full UI suites**

Run:

```bash
npm test -- --run src/pages/DailyResultDetailPage.test.tsx
npm test -- --run
```

Expected: PASS, with no request containing `patternId=__OTHER__`.

- [ ] **Step 5: Commit the UI slice**

```bash
cd ..
git add compare-ui/src/api/types.ts compare-ui/src/pages/DailyResultDetailPage.tsx compare-ui/src/pages/DailyResultDetailPage.test.tsx
git commit -m "feat: show collapsed field pattern summary"
```

### Task 6: Full verification, embedded frontend, and latest source ZIP

**Files:**
- Generated: `compare-ui/dist/**`
- Generated/staged into ZIP: `compare-service/src/main/resources/static/**`
- Generated: `target/tss-compare-source-1.0.0-SNAPSHOT.zip`
- Generated: `target/tss-compare-source-1.0.0-SNAPSHOT.zip.sha256`
- Consumed without printing: `target/bd-postgresql-overlay-20260923/config/bd.env`

**Interfaces:**
- Consumes: all committed implementation slices and the existing encrypted deployment configuration.
- Produces: one verifiable source ZIP that can build both Service and Spark artifacts on the intranet.

- [ ] **Step 1: Run all Maven tests with the supported local profile**

Run:

```bash
export JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
mvn -P'!bes',local-servlet-tests -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true test
```

Expected: BUILD SUCCESS with all Service and Spark tests passing.

- [ ] **Step 2: Run the frontend tests and production build**

Run:

```bash
cd compare-ui
npm test -- --run
npm run build
cd ..
```

Expected: all Vitest tests pass and `compare-ui/dist/index.html` exists.

- [ ] **Step 3: Check the working tree and commit generated tracked assets only if this repository tracks them**

Run:

```bash
git status --short
```

If tracked `compare-ui/dist/**` changed, commit those tracked build outputs; do not add untracked Maven `target/` directories:

```bash
git add -u compare-ui/dist
git commit -m "build: refresh embedded frontend assets"
```

- [ ] **Step 4: Generate the clean source ZIP**

Require a clean worktree, then run:

```bash
./bin/package-source.sh
```

Expected: `target/tss-compare-source-1.0.0-SNAPSHOT.zip` and its SHA-256 file are created from committed source, with `compare-ui/dist` copied into Service static resources.

- [ ] **Step 5: Inject the existing encrypted deployment configuration without displaying it**

Use a temporary directory, copy `target/bd-postgresql-overlay-20260923/config/bd.env` to `tss-compare-source-1.0.0-SNAPSHOT/config/bd.env`, rebuild the ZIP, and regenerate its SHA-256. Validate only key names and encrypted-value shape; never print values. Abort if the source config is absent, contains `JASYPT_ENCRYPTOR_PASSWORD`, or contains a non-`ENC(...)` metadata password.

- [ ] **Step 6: Verify ZIP contents and secrets policy**

Check with `unzip -t` and filename-only `unzip -Z1` that the archive contains:

```text
tss-compare-source-1.0.0-SNAPSHOT/compare-service/
tss-compare-source-1.0.0-SNAPSHOT/compare-spark/
tss-compare-source-1.0.0-SNAPSHOT/compare-service/src/main/resources/static/index.html
tss-compare-source-1.0.0-SNAPSHOT/sql/add_bcomp_pattern_overflow_new.sql
tss-compare-source-1.0.0-SNAPSHOT/compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/PatternCounts.java
tss-compare-source-1.0.0-SNAPSHOT/config/bd.env
tss-compare-source-1.0.0-SNAPSHOT/SOURCE-COMMIT.txt
```

Extract into a temporary directory and verify: `SOURCE-COMMIT.txt` equals `git rev-parse HEAD`; Service and Spark POMs exist; static HTML references built assets that exist; the migration and Top127 tests exist; `TSS_METADATA_PASSWORD` is encrypted; `JASYPT_ENCRYPTOR_PASSWORD` and known plaintext password forms are absent. Run `shasum -a 256 -c target/tss-compare-source-1.0.0-SNAPSHOT.zip.sha256`.

- [ ] **Step 7: Record deployment order in the handoff**

Tell the operator to stop application/in-flight Spark jobs, execute `sql/add_bcomp_pattern_overflow_new.sql`, compile the source ZIP on the intranet, and replace both artifacts together:

```text
compare-service/target/compare-service-1.0.0-SNAPSHOT.jar -> /home/ccbs/bd/lib/
compare-spark/target/compare-spark-1.0.0-SNAPSHOT.jar     -> /home/ccbs/bd/jobs/
```

Then restart the application and rerun a failed comparison. Do not claim the field exists until the migration has succeeded.
