# Safe Task Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every failed comparison task expose a safe, actionable failure stage and cause while preserving credential secrecy, then ship one source ZIP containing the latest Service/Spark sources, embedded frontend, and deployment `bd.env`.

**Architecture:** Add one credential-redacting diagnostic formatter in the service module. Replace the boolean-only completion check with a completion snapshot so the worker can distinguish a missing task row, a Spark task-state mismatch, and missing result counts after a `FINISHED` Spark application. Persist the bounded diagnostic in `FTASK_REASON`, log the same correlation-safe summary, and retain generic API behavior.

**Tech Stack:** Java 17, Spring Boot 3.3.10, JDBC/Hikari, Spark Launcher, JUnit 5, React/Vite, Bash/ZIP packaging.

**Spec:** Conversation-approved safe-diagnostics design from 2026-09-24; no separate design page is required because this is a bug-fix-only change under the Engineering Design Constitution.

## Global Constraints

- Never log or persist database passwords, Jasypt keys, complete JDBC URLs, or the 21 Spark application arguments.
- Persisted failure reasons must be bounded to 500 characters.
- A Spark application in `FINISHED` state is successful only when the metadata task row is state `3` and both source counts are non-null.
- The delivery ZIP must contain both Maven modules, embedded frontend assets, and `config/bd.env`, but not plaintext passwords or `JASYPT_ENCRYPTOR_PASSWORD`.

## Review Focus

- Exception messages containing `password=...` must be redacted while preserving exception classes and safe text.
- Nested causes and null exception messages must still produce useful summaries.
- Missing task rows after Spark completion must be distinguishable from incomplete counts.
- Existing Spark-written failure reasons must not be overwritten by the service.
- A complete state-3 task with zero counts must be accepted as complete.

---

### Task 1: Credential-safe diagnostic summaries

**Files:**
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/SafeDiagnostics.java`
- Create: `compare-service/src/test/java/cn/sunline/tss/compare/SafeDiagnosticsTest.java`

**Interfaces:**
- Produces: `SafeDiagnostics.summary(Throwable): String`, capped at 500 characters for task rows, and `SafeDiagnostics.logDetail(Throwable): String`, capped at 4000 characters with sanitized stack frames for logs.

- [ ] Write tests for nested causes, null messages, password/JDBC redaction, and length bounding.
- [ ] Run the focused test and confirm RED because `SafeDiagnostics` does not exist.
- [ ] Implement the minimal formatter and sanitizer.
- [ ] Run the focused test and confirm GREEN.

### Task 2: Diagnose FINISHED-without-result tasks

**Files:**
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/LegacyRepository.java`
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/TaskWorker.java`
- Modify: `compare-service/src/test/java/cn/sunline/tss/compare/LegacyRepositoryTest.java`
- Modify: `compare-service/src/test/java/cn/sunline/tss/compare/TaskWorkerTest.java`

**Interfaces:**
- Produces: `LegacyRepository.CompletionStatus` with `complete()` and `failureReason()`.
- Consumes: `SafeDiagnostics.summary(Throwable)` from Task 1.

- [ ] Write repository tests for complete zero counts, missing row, incomplete counts, and existing Spark failure reason.
- [ ] Write worker tests proving incomplete completion is logged/persisted and caught exceptions use sanitized summaries.
- [ ] Run focused tests and confirm RED against the boolean-only implementation.
- [ ] Implement completion snapshots, bounded failure persistence, and safe worker logging.
- [ ] Run focused tests and confirm GREEN.

### Task 3: Build and verify the self-contained source delivery

**Files:**
- Generated: `compare-ui/dist/**`
- Generated: `target/tss-compare-source-1.0.0-SNAPSHOT.zip`
- Generated: `target/tss-compare-source-1.0.0-SNAPSHOT.zip.sha256`

**Interfaces:**
- Consumes: Service/Spark sources from Tasks 1-2 and existing `target/bd-postgresql-overlay-20260923/config/bd.env`.
- Produces: one source ZIP suitable for the documented intranet Maven build.

- [ ] Run all service tests available with the local servlet profile and all Spark tests.
- [ ] Run frontend tests and production build.
- [ ] Generate the source ZIP and add the configured `config/bd.env`.
- [ ] Verify ZIP integrity, embedded frontend, both modules, diagnostic sources/tests, correct encrypted password, absence of plaintext password/key, and SHA-256.
