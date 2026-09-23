START TRANSACTION;

TRUNCATE TABLE tss_bcomp_field_new;
TRUNCATE TABLE tss_bcomp_table_sql_new;
TRUNCATE TABLE tss_bcomp_conf_new;

INSERT INTO tss_bcomp_conf_new
  (bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,bcomp_range,bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy,bcomp_sample_limit)
VALUES
(1,'dept','acct_master','账户主表比对','2','1','3','1',16,'HASH',37);

INSERT INTO tss_bcomp_field_new
  (bcomp_index,field_name,field_type,field_memo,field_old_index,field_new_index,field_index_flag,field_enum_flag)
VALUES
(1,'acct_no','1','账号','1','1','1','0'),
(1,'tenant_id','1','租户','2','2','1','0'),
(1,'balance','1','余额','3','3','0','0');

INSERT INTO tss_bcomp_table_sql_new
  (bcomp_index,orig_sql,orig_database_id,dest_sql,dest_database_id)
VALUES
(1,'(select acct_no,tenant_id,balance from acct_master) orig',1,'(select acct_no,tenant_id,balance from acct_master) dest',2);

COMMIT;

-- Version: 20260921-160000
-- Tables: 1
-- Fields: 3
