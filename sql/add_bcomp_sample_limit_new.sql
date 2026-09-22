-- Run in the metadata schema before upgrading service and Spark.
-- Preserve NULL for existing rows: they keep using BD_DIFF_DETAIL_LIMIT at runtime.
BEGIN;
ALTER TABLE TSS_BCOMP_CONF_NEW ADD COLUMN BCOMP_SAMPLE_LIMIT INT;
COMMIT;
