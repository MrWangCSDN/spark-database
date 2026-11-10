-- Run once in the GDB/MySQL metadata database used by service and Spark.
-- Stop bd and drain Spark jobs first. Existing configuration rows keep NULL reviser values.
-- MySQL-compatible DDL implicitly commits; START TRANSACTION cannot make this upgrade atomic.
ALTER TABLE TSS_BCOMP_CONF_NEW
    ADD COLUMN (
        REVISER_NAME VARCHAR(100),
        REVISER_USERNAME VARCHAR(100)
    );
