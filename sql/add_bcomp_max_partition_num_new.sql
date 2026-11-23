-- Run once in the metadata schema, before upgrading service and Spark.
-- Stop bd and wait for in-flight Spark jobs to end. Existing rows receive 64.
BEGIN;
ALTER TABLE TSS_BCOMP_CONF_NEW ADD COLUMN BCOMP_MAX_PARTITION_NUM INT NOT NULL DEFAULT 64
    CHECK (BCOMP_MAX_PARTITION_NUM BETWEEN 1 AND 256);
COMMIT;
