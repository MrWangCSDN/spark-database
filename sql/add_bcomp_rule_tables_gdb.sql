-- 已执行旧版六表脚本的环境，只执行本补充脚本。
-- 首次部署请执行 create_bcomp_new.sql，不要重复执行本文件。
-- 执行前设置与 bd 服务及 Spark 相同的 the selected metadata schema。
START TRANSACTION;

-- 三张规则表：首次创建空表，不复制旧数据。
CREATE TABLE TSS_BCOMP_ENUM_NEW (
    CONV_ENUM_INDEX BIGINT,
    ORIG_ENUM_VALUE VARCHAR(200),
    DEST_ENUM_VALUE VARCHAR(200)
);
CREATE INDEX idx_bcomp_enum_new_id ON TSS_BCOMP_ENUM_NEW (CONV_ENUM_INDEX);

CREATE TABLE TSS_IGNORE_VALUE_NEW (
    IGNORE_ID BIGINT,
    IGNORE_VALUE VARCHAR(200)
);
CREATE INDEX idx_ignore_value_new_id ON TSS_IGNORE_VALUE_NEW (IGNORE_ID);

-- 仅保留当前 Spark 预编译查询需要的字段；当前代码不执行该查询。
CREATE TABLE TSS_BCOMP_DIFFFIELD_NEW (
    BCOMP_INDEX BIGINT
);
CREATE INDEX idx_bcomp_difffield_new_id ON TSS_BCOMP_DIFFFIELD_NEW (BCOMP_INDEX);

COMMIT;
