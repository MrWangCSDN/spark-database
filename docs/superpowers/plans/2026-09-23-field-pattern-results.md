# Field-pattern result distribution and sample default

> For agentic workers: implement and verify each bounded task. Design source: Obsidian `01 Engineering/bd/每日核对结果-{系统设计,数据模型,API接口}.md` and Axon registration design.

Goal: New Axon registrations and bd fallback default to 100 samples; every exact set of differing fields gets an exact count and its own bounded sample list per table/business date.

Architecture: Spark compare records the complete ordered set of differing field names, aggregates exact long counts plus per-pattern top-K samples, merges without Executor side effects; Driver publishes summary/patterns/samples atomically. Existing type2/3 summary and samples remain. New fixed table stores pattern counts and field JSON. PatternID SHA256 of JSON. Existing results show no inferred historical patterns. Cap pattern kinds at128 and total samples at20000; fail publication if exceeded.

- [x] Axon default: V76 migration changes column defaults to100 without rewriting existing data; update DTO defaults, service create defaults, frontend new registration defaults/history null fallback, tests and build.
- [x] bd defaults: global fallback100 in config/launcher/Spark/env, explicit values unchanged; migration script for existing metadata deployment as needed.
- [x] Spark: exact pattern counts and top-K per pattern; duplicate and retry semantics; guard, ID/JSON collision check; tests.
- [x] DB publisher: new pattern table, detail pattern ID column, atomic replace, preservation on failure and cleanup updates; migration and full DDL/tests.
- [x] API/UI: versioned pattern list, filter samples by patternId, exact counts and saved counts, expired/old-result behavior, tests/browser build.
- [x] Cross-project verification, independent review, docs Git closure and delivery instructions.
