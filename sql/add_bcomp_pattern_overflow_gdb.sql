-- Existing bd schema: execute once after stopping bd and all in-flight Spark jobs.
START TRANSACTION;
ALTER TABLE TSS_BCOMP_FIELD_DIFF_NEW
    ADD COLUMN COLLAPSED_PATTERN_COUNT INT NOT NULL DEFAULT 1;
ALTER TABLE TSS_BCOMP_FIELD_DIFF_NEW
    ADD CONSTRAINT chk_bcomp_field_diff_collapsed_count
    CHECK (COLLAPSED_PATTERN_COUNT > 0);
COMMIT;
