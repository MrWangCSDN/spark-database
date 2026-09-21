# Primary-key hash partition implementation plan

**Goal:** Authorized Axon operators configure only a per-table partition count, carried through immutable snapshots and SQL into Spark primary-key hash JDBC reads.
**Spec:** /Users/java/obsidian-hash-partition/01 Engineering/axon-link-server/回放数据库比对字段登记-主键哈希分区-设计.md (isolated worktree of the canonical Obsidian vault; existing main workspace edits preserved).
**Architecture:** Three independent code boundaries share the fixed contract below. Use writing-plans and dispatching-parallel-agents workflows; parent implements receiver/Spark while bounded agents implement Axon producer and UI. No deployment or code push.

## Shared contract and constraints

- partitionNum integer 1..256, default 1. HASH at count 1 is plain single JDBC read; >1 database hash predicates over all FIELD_INDEX_FLAG keys.
- Axon option canConfigurePartitions; dedicated PUT /{id}/partitioning body {version,partitionNum}, R<Registration> result including partitionNum.
- Axon authorization property replay-database-comparison.partition-admin-emp-nos, empty denies all. Match server-resolved authenticated empNo only.
- New CONF SQL columns append bcomp_partition_num,bcomp_shard_strategy; values N,'HASH'. FIELD and SQL column lists unchanged. Accept legacy export.
- No normal-edit overwrite of partitionNum; actual changes only bump version/audit; include snapshots and fingerprints, preserve historical downloads.
- Spark resolves actual result column names by old/new positional metadata, includes all keys. Unknown dialect/function fails rather than disguising serial reads.
- No changes to CLAUDE.md/.claude, no overwrite of unrelated modifications, preserve legacy RANGE/NTILE.

## Tasks

- [x] Task 1 Axon backend: add migration, partitionNum model propagation, permission service/property/options, dedicated update with optimistic concurrency/audit, version/hash and exporter. Tests first: unauthorized denied, bounds, no-op, normal-edit preservation, snapshot/export immutable. Run focused dbcompare Maven tests.
- [x] Task 2 Axon Vue UI: add API call and permission-gated per-table one-number dialog, preserve ordinary editor and handle conflict/errors; tests for hidden controls, authorized submit, integer bounds, refresh. Run focused Vitest and build.
- [x] Task 3 compare-service importer: test accepted old/new scripts, HASH bounds and strategy validation, rollback; implement exact optional CONF schema and transactional value checks. Run ConfigurationApiTest and service suite.
- [x] Task 4 Spark: test complete/disjoint bucket predicates including string composite keys and nulls, aliases differing per side, single count, missing keys/unsupported dialect. Implement bounded hash planner separate class, hook before legacy partition detection, and validate actual local Spark result counts/differences. Run Spark tests.
- [x] Task 5 Integration/review: import actual generated new SQL into receiver tests, inspect diff and permissions cross-paths, build both projects and frontend, fresh independent review; fix confirmed issues and rerun covering checks. Document deployment/allowlist and dialect limits.

## Review focus

Unauthorized regular update cannot replace tuning; old snapshots default correctly; mutation is versioned and audited; hash predicates cover null/negative/composite keys exactly once; planned count does not silently degrade; source-driver function support is not claimed without evidence.

## Decisions / progress

User explicitly chose primary-key hash and only partition count. Default=1 preserves existing execution. A maximum of 256 prevents unbounded query fanout; deployment capacity may warrant a lower value. Documentation uses a clean vault worktree because the canonical checkout contains unrelated edits; do not commit or stash them.

2026-09-21: Task 2 commit 72571e2; 441 frontend tests/build passed, independent focused review 78 tests passed. Task 3 service suite 71 tests, zero failures/errors, one real-GaussDB opt-in skip; independent ConfigurationApiTest 16 passed. Docs committed/pushed on isolated vault branch 49e9cc0.

2026-09-21 final: Axon backend commits 5720985 and 81039c0; full backend suite 858 tests, zero failures/errors, two existing skips. Full Axon frontend suite 441 tests and production build passed. Full bd Maven suite passed: compare-service 72 tests (one opt-in GaussDB skip), compare-spark 17 tests. Actual Axon generator fixture with partitionNum=16 and a composite primary key is imported by ConfigurationApiTest. Independent reviews found and verified fixes for strict version JSON, strategy-error fallback, and MySQL/OceanBase cursor/timeouts; re-reviews reported no remaining important findings. Real vendor databases remain a deployment validation item.
