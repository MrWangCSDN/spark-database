START TRANSACTION;

TRUNCATE TABLE tss_bcomp_field_new;
TRUNCATE TABLE tss_bcomp_table_sql_new;
TRUNCATE TABLE tss_bcomp_conf_new;

INSERT INTO tss_bcomp_conf_new
  (bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,bcomp_range,bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy,bcomp_sample_limit,reviser_name,reviser_username,bcomp_max_partition_num)
VALUES
(1,'dept','acct_master',CONVERT(0xe8b4a6e688b75ce6988ee7bb8627e6b3a8e6af94e5afb9 USING utf8mb4),'2','1','3','1',1,'HASH',100,CONVERT(0x415c6e42 USING utf8mb4),NULL,100),
(2,'loan','loan_detail','贷款
明细比对','2','1','3','1',1,'HASH',100,'李四',NULL,256);

INSERT INTO tss_bcomp_field_new
  (bcomp_index,field_name,field_type,field_memo,field_old_index,field_new_index,field_index_flag,field_enum_flag)
VALUES
(1,'id','1',CONVERT(0xe7bc96e58fb75ce4bba3e7a08127e6b3a8 USING utf8mb4),'1','1','1','0'),
(1,'balance','1','金额
现值','2','2','0','0'),
(2,'id','1',CONVERT(0xe7bc96e58fb75ce4bba3e7a08127e6b3a8 USING utf8mb4),'1','1','1','0'),
(2,'balance','1','金额
现值','2','2','0','0');

INSERT INTO tss_bcomp_table_sql_new
  (bcomp_index,orig_sql,orig_database_id,dest_sql,dest_database_id)
VALUES
(1,'(select id,balance from acct_master) orig',1,'(select id,balance from acct_master) dest',2),
(2,'(select id,balance from loan_detail) orig',1,'(select id,balance from loan_detail) dest',2);

COMMIT;

-- Version: named-safe-contract-20261124
-- Tables: 2
-- Fields: 4
