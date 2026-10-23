# GDB Database Dialect and Comparison Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add explicit GaussDB/GDB control-plane selection, GDB connection management, GDB/GaussDB mixed Spark comparisons with HASH partition reads, GDB DDL, deployment configuration, and an Axon import example.

**Architecture:** Keep the existing JDBC metadata model and 21 Spark argument protocol. Introduce a small service-side `MetadataSqlDialect` selected by `bd.metadata-db-type`; repositories use it only for pagination/row limits while writes and business SQL stay parameterized. Add a Spark GDB/MySQL-compatible dialect and HASH expression branch, while each task-side connection continues to select its own dialect from URL/driver metadata.

**Tech Stack:** Java 17, Spring Boot 3.3.10/JdbcTemplate/Hikari, Spark 3.5.3 SQL JDBC, JUnit 5, React/TypeScript/Vite, MySQL-compatible GDB JDBC driver supplied at deployment.

**Spec:** `/Users/java/obsidian/01 Engineering/bd/GDB数据库方言与比对支持-系统设计.md`

## Global Constraints

- `BD_METADATA_DB_TYPE` accepts only `GAUSSDB` or `GDB`, defaults to `GAUSSDB`, and never silently falls back after an explicit mismatch.
- GDB defaults use `com.mysql.jdbc.Driver` and `jdbc:mysql:` but driver class and URL remain configurable.
- Existing GaussDB/PostgreSQL behavior, 21 Spark arguments, `FIELD_INDEX_FLAG='1'` business keys, NULL/duplicate-key failures, and result semantics remain compatible.
- GDB vendor JARs are supplied by deployment and loaded from `drivers/`; the repository does not redistribute closed-source drivers.
- No production schema is created or modified automatically at service startup.
- SQL values remain parameter-bound; only backend-controlled sort fragments and dialect pagination syntax may be assembled.

## Review Focus

- An explicit GDB type with a GaussDB URL/driver must fail clearly during metadata startup rather than silently using the wrong pagination syntax; cover in `MetadataDbTypeTest`/preflight tests.
- GDB pagination must preserve the existing offset/limit meaning when parameter order changes to `LIMIT ? OFFSET ?`; cover every repository pagination family in H2 dialect tests.
- A custom GDB JDBC URL prefix must be recognized by Spark without changing the 21 argument protocol; cover `GdbJdbcDialectTest` and launcher configuration tests.
- Mixed GDB/GaussDB task-side connections must choose independent HASH dialects while sharing one partition count; cover `PrimaryKeyHashPlannerTest` and read-plan tests.
- GDB DDL/import examples must preserve all `_NEW` tables, constraints, and fixed Axon column order without PostgreSQL-only syntax; cover script lint/import parser tests.

## File Map

- Service dialect/config: create `compare-service/src/main/java/cn/sunline/tss/compare/metadata/MetadataDbType.java`, `MetadataSqlDialect.java`, `MetadataDialectConfiguration.java`, `MetadataDatabasePreflight.java`; modify `RuntimeConfiguration.java`, `JdbcCurrentSchemaPolicy.java`, `SparkJobLauncher.java`.
- Service repositories: modify `ConfigurationRepository.java`, `BatchRepository.java`, `TaskManagementRepository.java`, `DatabaseManagementRepository.java`, `DailyResultRepository.java`; add focused service tests under `compare-service/src/test/java/cn/sunline/tss/compare/metadata/` and repository/API regressions.
- GDB scripts/config/docs: create `sql/create_bcomp_gdb.sql`, GDB incremental scripts matching the current `sql/add_*_new.sql` set, `config/application-gdb.properties.example`, and `docs/examples/axon-config-gdb.sql`; modify `config/bd.env.example`, `config/application-local.properties.example`, `README.md`, and packaging checks.
- Connection UI: modify `compare-ui/src/components/DatabaseDrawer.tsx`, `compare-ui/src/database/currentSchema.ts`, related tests, and UI API/mock fixtures for GDB examples.
- Spark dialect: create `compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/GdbJdbcDialect.java` and its test; modify `SparkHandlerTable.java`, `PrimaryKeyHashPlanner.java`, `HashReadConfigurationTest.java`, `PrimaryKeyHashPlannerTest.java`, and add mixed-driver read-plan coverage.

### Task 1: Add metadata database type and pagination dialect

**Interfaces:** `MetadataDbType.parse(String) -> MetadataDbType`; `MetadataSqlDialect.page(String baseSql, long offset, int limit) -> MetadataSqlDialect.Page`; `MetadataSqlDialect.limit(String baseSql, int limit) -> MetadataSqlDialect.Page`; `Page.sql()` contains placeholders and `Page.parameters()` is ordered for JDBC.

- [ ] Write tests for default/normalized/invalid type values, GaussDB `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY`, GaussDB `FETCH FIRST ? ROWS ONLY`, GDB `LIMIT ? OFFSET ?`, GDB limit-only syntax, and exact parameter order.
- [ ] Run `mvn -pl compare-service -Dtest=MetadataDbTypeTest,MetadataSqlDialectTest test`; verify the new tests fail before implementation.
- [ ] Implement the enum, immutable dialect value object, and page result record under `compare-service/.../metadata/`; preserve the current GaussDB SQL text and use `LIMIT/OFFSET` only for GDB.
- [ ] Add `bd.metadata-db-type=${BD_METADATA_DB_TYPE:GAUSSDB}` to the application configuration and a Spring bean factory that parses it once.
- [ ] Run the focused tests and verify PASS.
- [ ] Commit: `feat: add metadata database dialect abstraction`.

### Task 2: Route service repositories and startup checks through the dialect

**Interfaces:** Repositories receive `MetadataSqlDialect`; all paginated queries use `Page.sql()` plus `Page.parameters()`; `MetadataDatabasePreflight` validates the configured type against `DatabaseMetaData` without logging credentials.

- [ ] Add failing repository tests for configuration, batch, task, database, and daily-result pagination on both H2-compatible GaussDB syntax and a GDB dialect SQL capture; assert returned page contents and parameter order.
- [ ] Add failing preflight tests for default GaussDB, explicit GDB, type/product mismatch, missing driver, and sanitized error messages.
- [ ] Refactor the five repositories to accept the dialect while keeping convenience constructors in tests where needed; replace every `OFFSET/FETCH` and `FETCH FIRST` occurrence found by `rg` with dialect-generated SQL, including fixed candidate limits.
- [ ] Update `RuntimeConfiguration`/Spring wiring so the metadata dialect and preflight use the same property as the data source; keep Hikari dynamic datasource compatibility.
- [ ] Extend `JdbcCurrentSchemaPolicy` tests to assert GDB/MySQL URLs and GDB driver names do not require `currentSchema`, while existing GaussDB/PostgreSQL cases still do.
- [ ] Run service metadata, database, batch, task, result, and configuration tests; verify no pagination query remains outside the dialect except documented task-side SQL.
- [ ] Commit: `feat: support GDB metadata SQL dialect in service repositories`.

### Task 3: Add GDB deployment configuration, DDL, and Axon script example

**Interfaces:** `sql/create_bcomp_gdb.sql` creates the same `_NEW` schema as the GaussDB full script using GDB-compatible types/syntax; each GDB incremental script maps one existing incremental migration; the example remains consumable by `AxonConfigScriptParser`.

- [ ] Add a script-lint test that rejects `search_path`, `FETCH FIRST`, PostgreSQL-only casts/types, and missing required tables/indexes in the GDB full script; assert all expected `_NEW` tables and unique sample index are present.
- [ ] Add an import-parser test that reads `docs/examples/axon-config-gdb.sql`, verifies configuration/field/SQL row counts, composite `FIELD_INDEX_FLAG='1'` keys, and `HASH` compatibility columns.
- [ ] Create the GDB full and incremental scripts by translating the current schema without changing table/column names or Axon fixed insert column order; use `LONGTEXT`, `BIGINT`, `INT`, `VARCHAR`, MySQL-compatible indexes/constraints, and transaction framing supported by the target GDB version.
- [ ] Add `config/application-gdb.properties.example` and extend `config/bd.env.example`/`application-local.properties.example` with metadata type, GDB URL, driver, password, and `spark.gdb.jdbc-url-prefixes` placeholders; keep secrets as environment placeholders.
- [ ] Update `bin/package.sh` checks and README deployment instructions to include external GDB driver placement, control-plane script choice, mixed comparison matrix, and rollback order.
- [ ] Run script lint, parser tests, package-content checks, and `git diff --check`; verify no credentials or vendor JAR is committed.
- [ ] Commit: `docs: add GDB deployment scripts and import example`.

### Task 4: Support GDB in connection management UI and service examples

**Interfaces:** `currentSchemaError(url, driver)` continues to return null for GDB/MySQL; the connection drawer offers `com.mysql.jdbc.Driver`, GDB URL placeholders, and explains that `currentSchema` is only for GaussDB/PostgreSQL.

- [ ] Add failing Vitest cases for `jdbc:gdb:`/`jdbc:mysql:` plus GDB driver names, and component assertions for GDB driver option/help text.
- [ ] Update `currentSchema.ts`, `DatabaseDrawer.tsx`, UI test fixtures, and database API tests without changing the request/response shape or password handling.
- [ ] Add service connection-management tests for creating/testing GDB URLs with no `currentSchema`, preserving encrypted passwords, and rejecting explicit GaussDB mismatches in preflight only.
- [ ] Run the focused UI and service tests and the frontend build; verify saved password material never appears in UI or responses.
- [ ] Commit: `feat: expose GDB database connections in management UI`.

### Task 5: Add Spark GDB/MySQL dialect and HASH partition support

**Interfaces:** `GdbJdbcDialect.register(String prefixes)` registers a Spark `JdbcDialect`; `PrimaryKeyHashPlanner.dialect(...)` returns `MYSQL` for GDB/MySQL metadata; `SparkHandlerTable` registers GDB prefixes from `spark.gdb.jdbc-url-prefixes` and includes the dialect in read-mode descriptions.

- [ ] Add failing `GdbJdbcDialectTest` for default/custom URL prefixes, MySQL/GDB URL recognition, cursor `beforeFetch`, and non-GDB rejection.
- [ ] Add failing `PrimaryKeyHashPlannerTest` assertions for GDB/MySQL dialect selection, CRC32 expression generation, quoted aliases, null handling, and composite-key bucket predicates; preserve the existing unavailable-hash failure behavior.
- [ ] Implement `GdbJdbcDialect` using MySQL-compatible cursor setup and a normalized prefix set; register it after Spark session creation with the configured prefix property while retaining `OpenGaussJdbcDialect`.
- [ ] Refactor `PrimaryKeyHashPlanner` dialect selection to identify GDB/MySQL by product, URL, or driver and generate the GDB-compatible hash expression; keep single-partition reads free of vendor hash functions.
- [ ] Update `SparkHandlerTable`/`SparkJobLauncher` to pass the configured prefix, add GDB cursor-fetch driver detection, and keep the 21 argument protocol unchanged.
- [ ] Add mixed-side read-plan coverage proving source and destination can choose different dialects with one shared partition count; verify mode strings disclose only dialect/category and partition count.
- [ ] Run focused Spark tests and the existing local Spark compatibility suite; verify no password/URL leakage in logs.
- [ ] Commit: `feat: support GDB Spark JDBC dialect and hash reads`.

### Task 6: Full verification and delivery documentation

- [ ] Run `mvn -pl compare-service,compare-spark -am test` with the repository's available dependencies; record any external GDB driver/repository limitation separately from code failures.
- [ ] Run frontend tests/build and `bin/package.sh` or the closest package verification available; confirm GDB config examples and scripts are present in the package.
- [ ] Run `rg` checks for hard-coded pagination syntax, missing GDB references, plaintext credentials, and accidental vendor JARs.
- [ ] Update README verification/deployment sections with the exact GDB rollout checklist: stop service, drain Spark, create schema, place driver on service and Spark nodes, set type/URL/driver, test both connections, run a minimal GDB-GDB and mixed task, then enable normal scheduling.
- [ ] Perform final `git diff --check`, review the changed-file list against this plan and the Obsidian design, and commit any final documentation/test corrections.
- [ ] Commit: `docs: document GDB verification and rollout`.
