# Effective Spark job artifact validation

**Goal** Ensure NAME-mode submissions inspect the actual configured Spark job JAR and expose an actionable deployment error when it lacks name resolution.

**Design** Obsidian `01 Engineering/bd/` comparison design set, commit `2b83079f4789b69988e4e30b2b809a858eca0e41`.

**Runtime compatibility update** Design commit `1bca4f000df9d85eb9292740230e0aa8950fd84d` adds the on-site Spark 3.4.0 build profile and the shared JDBC dialect APIs.

## Evidence

- The UI shows NAME mode while the failing worker trace uses the historical `lambda$RddToPair$4f96395$1` implementation at a positional read.
- Historical source commit `69eb8e0` has that method body and a `line.get` call at line 785, matching the reported trace.
- The original application-spark configuration selects a separate legacy app_resource. The startup script checks the packaged jobs file while the launcher submits the configured resource.
- A stale local module target JAR also retains the old lambda, whereas the verified source ZIP builds the new lambda.
- Current production main passed isolated 85-column/position-90 and 35-column/position-44 tests with the target projection reversed. POSITION mode rejected the invalid position during preflight.

## Implementation

- [x] Add normal-submit regressions for a legacy JAR, missing selected main, a modern JAR and POSITION compatibility.
- [x] Confirm the two NAME rejection regressions fail before implementation.
- [x] Resolve the effective resource to its real path and inspect required classes in NAME mode.
- [x] Log resource path, SHA-256, main and mode safely, and associate application IDs with the artifact hash.
- [x] Log the Driver's mode and actual loaded class source.
- [x] Run all backend tests and rebuild both modules through the packaging entrypoint.
- [x] Reproduce the Spark 3.4 compilation failure caused by Spark 3.5-only JDBC APIs.
- [x] Replace version-specific query-builder APIs with common JDBC LIMIT behavior and let Spark handle OFFSET.
- [x] Verify all 62 Spark tests against Spark 3.4.0 in an isolated build.
- [x] Verify the `spark-3.4` profile with Spark-only Jackson 2.14.2, and the default Spark 3.5.3 matrix.
- [x] Review the diff and verify a fresh source bundle with the existing frontend embedded.
- [x] Confirm the extracted source bundle builds and contains matching service and Spark artifacts.

## Boundaries

- NAME requires the selected main and the existing QueryFieldResolver entry in the actual app_resource JAR.
- POSITION preserves legacy job compatibility.
- No database migration or comparison algorithm change is required.
- The actual on-site app_resource and runtime classpath still require deployment verification.

## Verification before source packaging

- Launcher regressions passed through the normal submit entrypoint. Legacy NAME jobs fail with the effective path before Spark starts.
- Default runtime full packaging passed with service 146 tests (one real Gauss connection test skipped) and Spark 62 tests.
- Isolated Spark 3.4.0 full module regression passed all 62 tests. The compiled NAME lambda remains `$64ed6cd6$1` on both versions.
- The original imported Spark configuration contains a separate legacy app_resource path, so deployment must confirm the final effective value.

## Extracted-source verification

- The extracted source passed full packaging with `-Plocal-servlet-tests,spark-3.4`, Gauss JDBC 6.0.0 and skipped frontend downloads. Service 146 tests (one real Gauss connection test skipped), Spark 62 tests, zero failures/errors.
- The Spark test classpath confirms Spark 3.4.0 and Jackson 2.14.2. The backend retains its independent Jackson 2.17.3 dependency.
- The service JAR includes the artifact validation class and all three current frontend files. The Spark JAR includes name resolution and nullable-key support. All 20 SQL files match the checkout.
- On-site BES, real Gauss/GDB connections and the actual app_resource override still require deployment confirmation.
