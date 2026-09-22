# Per-table sample limit implementation plan

Goal: Axon config owns a per-category sample limit, default1000, editable by the partition permission holders, visible beside partition count in current and historical views, exported/imported and honored by bd.
Architecture: API sampleLimit; Axon registration/version columns sample_limit NOT NULL DEFAULT1000; current/old snapshots preserve their values. New SQL appends bcomp_sample_limit after bcomp_shard_strategy. Legacy download strips all extension columns. bd nullable BCOMP_SAMPLE_LIMIT overrides global fallback only when present; counts stay full.

- [x] Axon backend: migrations, strict integer0..10000 DTO, authenticated partition permission, audit, all copies and persistence, hash/version snapshot, SQL export and legacy conversion; test permissions and historical isolation.
- [x] Axon frontend: editor/default/validation/permission, list column immediately after partition count, view/history display and preservation, tests/build.
- [x] bd: parser supports old8, prior10 and new11 column SQL; DDL migration, runtime reads per-table limit with global fallback; exact-count/cap tests.
- [x] Cross-project review, integration fixture, verification, docs commit/push; source commits and final instructions.

Ruling: no extra permission prompt: user explicitly authorized this field and existing partition permission, and prior result threshold is0..10000. No external code push requested; only Obsidian docs push per constitution.

Validation: Axon backend190 tests pass; frontend114 feature tests/build pass (full suite451 pass, unrelated existing ReplayIssuePage column-width assertion1 fail). bd service99 tests pass with1 Gauss environment skip; Spark32 pass plus new migration test2 pass. Independent cross-project review found no actionable defects. Actual Axon-generated11-column SQL imported by bd persisted limits0,37,1000 correctly. Frontend default build embedded assets into Axon backend static directory. Real Gauss/Spark intranet validation remains onsite.
Deployment: Axon V75__replay_db_compare_sample_limit.sql; bd add_bcomp_sample_limit_new.sql before new service/Spark. New version needed for exported sample settings; immutable previously generated scripts stay unchanged.
