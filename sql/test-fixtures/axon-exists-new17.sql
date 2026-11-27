-- 请通过 BD 配置导入功能导入此 NEW17 脚本，由导入器维护固定配置标识映射；不要直接在元数据库执行。
START TRANSACTION;

TRUNCATE TABLE tss_bcomp_field_new;
TRUNCATE TABLE tss_bcomp_table_sql_new;
TRUNCATE TABLE tss_bcomp_conf_new;

INSERT INTO tss_bcomp_conf_new
  (bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,bcomp_range,bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy,bcomp_sample_limit,reviser_name,reviser_username,bcomp_max_partition_num,source_table_name,configuration_name,configuration_scope)
VALUES
(1,'dept','kgnb_chrg_item','收费项目比对','2','1','3','1',1,'HASH',100,'创建人','creator',64,'kgnb_chrg_item','存在_样本','SELECT kci.tenant_id, kci.item_id, kci.gntltr_id, kci.amount
FROM kgnb_chrg_item kci
WHERE EXISTS (
	SELECT 1
	FROM kgna_gntl_acct kga
	WHERE kga.gntltr_id = kci.gntltr_id
		AND kga.gntltr_bsn_stcd <> ''S''
)'),
(2,'dept','kgnb_chrg_item_1','收费项目比对','2','1','3','1',1,'HASH',-1,'创建人','creator',64,'kgnb_chrg_item','不存在_全量','SELECT kci.tenant_id, kci.item_id, kci.gntltr_id, kci.amount
FROM kgnb_chrg_item kci
WHERE NOT EXISTS (
	SELECT 1
	FROM kgna_gntl_acct kga
	WHERE kga.gntltr_id = kci.gntltr_id
		AND kga.gntltr_bsn_stcd <> ''S''
)'),
(3,'dept','kgnb_chrg_item_2','收费项目比对','2','1','3','1',1,'HASH',-1,'创建人','creator',64,'kgnb_chrg_item','存在_全量','SELECT kci.tenant_id, kci.item_id, kci.gntltr_id, kci.amount
FROM kgnb_chrg_item kci
WHERE EXISTS (
	SELECT 1
	FROM kgna_gntl_acct kga
	WHERE kga.gntltr_id = kci.gntltr_id
		AND kga.gntltr_bsn_stcd <> ''S''
)');

INSERT INTO tss_bcomp_field_new
  (bcomp_index,field_name,field_type,field_memo,field_old_index,field_new_index,field_index_flag,field_enum_flag)
VALUES
(1,'tenant_id','1','tenant_id','1','1','1','0'),
(1,'item_id','1','item_id','2','2','1','0'),
(1,'gntltr_id','1','gntltr_id','3','3','0','0'),
(1,'amount','1','amount','4','4','0','0'),
(2,'tenant_id','1','tenant_id','1','1','1','0'),
(2,'item_id','1','item_id','2','2','1','0'),
(2,'gntltr_id','1','gntltr_id','3','3','0','0'),
(2,'amount','1','amount','4','4','0','0'),
(3,'tenant_id','1','tenant_id','1','1','1','0'),
(3,'item_id','1','item_id','2','2','1','0'),
(3,'gntltr_id','1','gntltr_id','3','3','0','0'),
(3,'amount','1','amount','4','4','0','0');

INSERT INTO tss_bcomp_table_sql_new
  (bcomp_index,orig_sql,orig_database_id,dest_sql,dest_database_id)
VALUES
(1,'(select result_q."tenant_id" as "tenant_id",result_q."item_id" as "item_id",result_q."gntltr_id" as "gntltr_id",result_q."amount" as "amount" from (
SELECT kci.tenant_id, kci.item_id, kci.gntltr_id, kci.amount
FROM kgnb_chrg_item kci
WHERE EXISTS (
	SELECT 1
	FROM kgna_gntl_acct kga
	WHERE kga.gntltr_id = kci.gntltr_id
		AND kga.gntltr_bsn_stcd <> ''S''
)
) result_q) orig',1,'(select result_q."tenant_id" as "tenant_id",result_q."item_id" as "item_id",result_q."gntltr_id" as "gntltr_id",result_q."amount" as "amount" from (
SELECT kci.tenant_id, kci.item_id, kci.gntltr_id, kci.amount
FROM kgnb_chrg_item kci
WHERE EXISTS (
	SELECT 1
	FROM kgna_gntl_acct kga
	WHERE kga.gntltr_id = kci.gntltr_id
		AND kga.gntltr_bsn_stcd <> ''S''
)
) result_q) dest',2),
(2,'(select result_q."tenant_id" as "tenant_id",result_q."item_id" as "item_id",result_q."gntltr_id" as "gntltr_id",result_q."amount" as "amount" from (
SELECT kci.tenant_id, kci.item_id, kci.gntltr_id, kci.amount
FROM kgnb_chrg_item kci
WHERE NOT EXISTS (
	SELECT 1
	FROM kgna_gntl_acct kga
	WHERE kga.gntltr_id = kci.gntltr_id
		AND kga.gntltr_bsn_stcd <> ''S''
)
) result_q) orig',1,'(select result_q."tenant_id" as "tenant_id",result_q."item_id" as "item_id",result_q."gntltr_id" as "gntltr_id",result_q."amount" as "amount" from (
SELECT kci.tenant_id, kci.item_id, kci.gntltr_id, kci.amount
FROM kgnb_chrg_item kci
WHERE NOT EXISTS (
	SELECT 1
	FROM kgna_gntl_acct kga
	WHERE kga.gntltr_id = kci.gntltr_id
		AND kga.gntltr_bsn_stcd <> ''S''
)
) result_q) dest',2),
(3,'(select result_q."tenant_id" as "tenant_id",result_q."item_id" as "item_id",result_q."gntltr_id" as "gntltr_id",result_q."amount" as "amount" from (
SELECT kci.tenant_id, kci.item_id, kci.gntltr_id, kci.amount
FROM kgnb_chrg_item kci
WHERE EXISTS (
	SELECT 1
	FROM kgna_gntl_acct kga
	WHERE kga.gntltr_id = kci.gntltr_id
		AND kga.gntltr_bsn_stcd <> ''S''
)
) result_q) orig',1,'(select result_q."tenant_id" as "tenant_id",result_q."item_id" as "item_id",result_q."gntltr_id" as "gntltr_id",result_q."amount" as "amount" from (
SELECT kci.tenant_id, kci.item_id, kci.gntltr_id, kci.amount
FROM kgnb_chrg_item kci
WHERE EXISTS (
	SELECT 1
	FROM kgna_gntl_acct kga
	WHERE kga.gntltr_id = kci.gntltr_id
		AND kga.gntltr_bsn_stcd <> ''S''
)
) result_q) dest',2);

COMMIT;

-- Version: exists-fixture
-- Tables: 3
-- Fields: 12
