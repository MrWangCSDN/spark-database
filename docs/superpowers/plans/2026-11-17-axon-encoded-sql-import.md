# Axon encoded SQL import repair

**Goal** Import Axon custom queries containing backslashes through the bd configuration page while preserving their exact text.

**Design** Obsidian `01 Engineering/bd/` import and management-console design sets, commit `bf0e943a30df05fe0c1dc063ebd27df06be13d18`.

**Approach** The fixed-format parser recognizes Axon's UTF-8 hex expression only in `orig_sql` and `dest_sql`. It decodes those values and ordinary SQL string literals into JDBC parameters. The repository uses the existing transaction to execute validated insert templates with their parameters.

## Implementation and regression checks

- [x] Reproduce two normal API import failures before implementation with valid Axon hex query values.
- [x] Add API tests for exact query storage and retrieval, mixed literal/hex multi-row ordering and invalid encoding rollback.
- [x] Parse fixed hex values with strict UTF-8 decoding and reject NUL.
- [x] Bind both ordinary and encoded SQL text with JDBC parameters, preserving backslashes and standard SQL single quotes.
- [x] Run focused tests and the full Maven suite under JDK 17.
- [x] Verify output from the current Axon generator against the updated import path.
- [x] Review the code and package the source ZIP with the existing built frontend.
- [x] Extract the source ZIP and verify the intranet packaging entrypoint with frontend downloads disabled.
- [x] Verify source identity, frontend embedding, SQL files and archive checksum.

## Constraints

- Scope is the bd configuration import API and page workflow.
- Fixed script tables, column layouts, HASH/sample checks, active-task protection and transaction rollback apply throughout.
- Only the two query-text columns accept the fixed hex expression. Other columns use the existing literal rules.
- The UTF-8 decoder preserves Unicode, quotes, line breaks and single or consecutive backslashes.
- Existing database schemas and Spark comparison rules are used.
- Local build verification uses `local-servlet-tests` and Gauss JDBC `6.0.0`. Formal deployment retains the configured BES and Gauss driver versions.

## Verification before source packaging

- Two valid API imports failed with HTTP 400 before the repair.
- Focused configuration and GDB tests passed 34 tests.
- Full backend regression passed with service 142 tests (one real Gauss connection test skipped) and Spark 61 tests.
- Current Axon generator output for both ordinary and backslash-containing custom SQL passed the bd repository import into H2, preserving backslashes.
- Independent diff review found no required corrections.

## Extracted-source verification

- The extracted source ZIP passed `bin/package.sh -Plocal-servlet-tests -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true clean`, including both full test suites and release TAR generation.
- Packaged backend classes match the tested importer. All three embedded frontend files and all 20 SQL files match the checkout.
- Real BES, GaussDB and MySQL/GDB deployment connections were not exercised locally.
