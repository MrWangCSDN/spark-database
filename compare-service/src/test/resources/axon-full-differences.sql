START TRANSACTION;

TRUNCATE TABLE tss_bcomp_field_new;
TRUNCATE TABLE tss_bcomp_table_sql_new;
TRUNCATE TABLE tss_bcomp_conf_new;

INSERT INTO tss_bcomp_conf_new
  (bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,bcomp_range,bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy,bcomp_sample_limit,reviser_name,reviser_username,bcomp_max_partition_num)
VALUES
(1,'dept','acct_master','账户主表比对','2','1','3','1',1,'HASH',-1,'张三','zhangsan',64);

INSERT INTO tss_bcomp_field_new
  (bcomp_index,field_name,field_type,field_memo,field_old_index,field_new_index,field_index_flag,field_enum_flag)
VALUES
(1,'acct_no','1','账号','1','1','1','0'),
(1,'customer_no','1','客户号','2','2','0','0');

INSERT INTO tss_bcomp_table_sql_new
  (bcomp_index,orig_sql,orig_database_id,dest_sql,dest_database_id)
VALUES
(1,'(select acct_no,customer_no from acct_master) orig',1,'(select acct_no,customer_no from acct_master) dest',2);

COMMIT;

-- Version: 20260914-172637
-- Tables: 1
-- Fields: 2
