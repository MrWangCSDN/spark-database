# Field-name matching implementation plan

**Goal** Default comparison to case-insensitive configured English field names and SQL labels, failing on missing or duplicate names.

**Architecture** Resolve each side's JDBC projection before counting and HASH planning. Pass copied field rules with the resolved positions to existing readers, business-key extraction and comparison logic. The service, launcher and UI share one configuration switch.

**Tech stack** Java 17, Spark 3.5.3, JDBC, Spring Boot 3.3.10, React.

**Design** Obsidian `01 Engineering/bd/` library comparison and management-console design sets, commit `4acd7f13b259de65587cfe9835c90383e1ae15e6`.

## Constraints

- Default `compare.field-match-by-name=true`, forwarded as `spark.compare.fieldMatchByName`.
- NAME uses trimmed, case-insensitive JDBC labels. Missing or duplicate names fail before data reads.
- Explicit false preserves POSITION mode and existing constructor behavior.
- Preserve nullable-key multiplicity matching and bounded results.
- Existing schema and Axon import format remain usable.
- Embed the rebuilt frontend in the backend source bundle for intranet builds.

## Review focus

- Historical positions 37, zero, NULL and duplicate positions must be ignored in NAME.
- Reordered outputs and aggregate aliases must identify the same logical fields and business keys.
- Failure must preserve the preceding successful daily result.
- POSITION must retain side-specific aliases and index validation.
- JDBC field-rule numeric types must survive copied rows and production Kryo serialization.

## Tasks

### 1. JDBC projection resolution and Spark integration

- [x] Add `QueryFieldResolver.resolve(Connection,String,List<Row>,String,String,boolean)` and tests for labels, missing names, duplicates, numeric schema types and POSITION boundaries.
- [x] Add `FieldNameComparisonTest` using H2 and production main with wrong saved positions, reordered columns, strict errors and preserved successful results. Run it before integration and confirm the failure reproduces position-based behavior.
- [x] Resolve both sides in `SparkHandlerTable.SparkRun` before key validation, counts and HASH planning. Use the resolved copies throughout the existing comparison pipeline.
- [x] Persist safe field-resolution errors in task reasons and keep other failures under existing diagnostics.
- [x] Make configuration ordering deterministic and pin legacy alias fixtures to explicit POSITION mode.
- [x] Run all Spark tests under JDK 17.

### 2. Service, configuration and UI

- [x] Add mode to options, validation, batch preflight and launcher configuration. Keep historical public constructor signatures.
- [x] Hide position controls in NAME and validate unique case-insensitive names. Preserve the field payload for compatibility.
- [x] Add API, batch and frontend regressions for NAME and POSITION.
- [x] Document defaults, SQL aliases and deployment switches in configuration templates and build instructions.
- [x] Run service and frontend test suites and rebuild UI with Node 22.14.0 and npm 10.9.2.

### 3. Review and intranet bundle

- [x] Review the full diff against the approved behavior and correct findings.
- [x] Commit the source change and produce a versioned source ZIP with embedded frontend.
- [x] Extract the ZIP and run the normal packaging entrypoint with frontend downloads disabled. Use local servlet and public Gauss driver overrides only for local verification.
- [x] Verify SQL byte equality, frontend embedding, source commit and archive checksum. Report local-only dependency overrides and untested production components.

## Verification recorded before packaging

- Original entrypoint regression failed with `HASH主键位置超出查询列范围: 99` before integration.
- Service suite completed 139 tests with zero failures and one real Gauss connection test skipped.
- Spark suite completed 61 tests with zero failures, including missing/duplicate labels and production Kryo.
- Frontend completed 76 tests and TypeScript/Vite build under Node 22.14.0 and npm 10.9.2.

## Extracted-source packaging verification

- `bin/package.sh -Plocal-servlet-tests -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true clean` completed successfully from the extracted source ZIP.
- Service 139 tests, zero failures/errors, one real Gauss integration test skipped. Spark 61 tests, zero failures/errors.
- Backend JAR static resources matched the three UI dist files byte-for-byte. The Spark JAR contains field-name resolution and nullable-key matching.
- Generated release TAR contains both JARs, the default NAME switch and all 20 SQL files matching the checkout.
- Formal BES dependencies, Gauss JDBC 6.0.0-htrunk8 and the on-site Spark 3.4.0 combination require verification in the deployment environment.
