-- 已有 bd 元数据库执行一次。只增加可空领域快照列，不修改历史结果。
-- 先设置与 bd 服务、Spark 使用的相同 search_path。
BEGIN;
ALTER TABLE TSS_BCOMP_RESULT_NEW ADD COLUMN IF NOT EXISTS CONFIGURATION_MODULE VARCHAR(100);
COMMIT;
