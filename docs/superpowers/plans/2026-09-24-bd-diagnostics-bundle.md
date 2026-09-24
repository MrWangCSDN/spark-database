# BD Diagnostics Bundle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver one ZIP containing the exact read-only log and metadata-schema checks requested by the user.

**Architecture:** Generate three standalone files in a staging directory: one Bash log scanner, one PostgreSQL-compatible schema inspection script, and one Chinese usage guide. Package only those files and produce a SHA-256 checksum.

**Tech Stack:** Bash, PostgreSQL/openGauss information schema SQL, ZIP, SHA-256.

**Spec:** Conversation-approved design from 2026-09-24; the Engineering Design Constitution exempts this one-time script utility from a separate design document.

## Global Constraints

- Every command and SQL statement is read-only.
- The bundle contains no passwords, JDBC URLs, encrypted values, or environment files.
- The Bash script defaults to `/home/ccbs/bd/logs/bd-console.log` and accepts an optional log path argument.
- The SQL script only inspects `current_schema()`, required result tables, and required columns.

## Review Focus

- A missing log file must produce a clear non-zero failure without creating files on the server.
- Paths containing spaces must remain safe through shell quoting.
- No SQL may contain DDL or DML.
- The ZIP must contain exactly the three documented files under one top-level directory.
- The checksum must validate the final ZIP.

---

### Task 1: Build and verify the read-only diagnostics bundle

**Files:**
- Generate: `target/bd-diagnostics-check-20260924/check-bd-log.sh`
- Generate: `target/bd-diagnostics-check-20260924/check-bd-schema.sql`
- Generate: `target/bd-diagnostics-check-20260924/README.txt`
- Generate: `target/bd-diagnostics-check-20260924.zip`
- Generate: `target/bd-diagnostics-check-20260924.zip.sha256`

**Interfaces:**
- Consumes: optional positional Bash argument containing the console log path.
- Produces: diagnostic text on stdout and read-only SQL result sets.

- [ ] Create a temporary fixture log containing a PostgreSQL exception and verify the planned scanner command returns that line.
- [ ] Create `check-bd-log.sh` with `set -euo pipefail`, file validation, and the approved exception patterns.
- [ ] Run `bash -n` and execute it against the fixture; expect the PostgreSQL exception and surrounding task diagnostic to be printed.
- [ ] Create `check-bd-schema.sql` containing only the approved `SELECT` statements against `current_schema()`, `information_schema.tables`, and `information_schema.columns`.
- [ ] Create `README.txt` with the two execution commands and a warning that the scripts do not repair schema.
- [ ] Scan the SQL for `INSERT`, `UPDATE`, `DELETE`, `ALTER`, `CREATE`, `DROP`, and `TRUNCATE`; expect no matches.
- [ ] Scan all three files for credentials and environment assignments; expect no matches.
- [ ] Package the directory, run `unzip -t`, confirm the archive contains exactly three files, and verify its SHA-256 file.
