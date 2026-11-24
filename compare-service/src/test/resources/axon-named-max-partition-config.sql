-- Named Axon profile contract: full-table comparison, composite primary key, explicit HASH cap.
START TRANSACTION;
TRUNCATE TABLE TSS_BCOMP_FIELD_NEW;
TRUNCATE TABLE TSS_BCOMP_TABLE_SQL_NEW;
TRUNCATE TABLE TSS_BCOMP_CONF_NEW;
INSERT INTO TSS_BCOMP_CONF_NEW
  (bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,bcomp_range,bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy,bcomp_sample_limit,reviser_name,reviser_username,bcomp_max_partition_num)
VALUES (1,'dept','account_detail','账户明细','2','1','3','1',1,'HASH',100,'Excel负责人',NULL,256);
INSERT INTO TSS_BCOMP_FIELD_NEW
  (bcomp_index,field_name,field_type,field_memo,field_old_index,field_new_index,field_index_flag,field_enum_flag)
VALUES (1,'TENANT_ID','1','租户号',1,1,'1','0'),
       (1,'ACCOUNT_ID','1','账号',2,2,'1','0'),
       (1,'BALANCE','1','余额',3,3,'0','0');
INSERT INTO TSS_BCOMP_TABLE_SQL_NEW
  (bcomp_index,orig_sql,orig_database_id,dest_sql,dest_database_id)
VALUES (1,'(select TENANT_ID,ACCOUNT_ID,BALANCE from account_detail) orig',1,
          '(select TENANT_ID,ACCOUNT_ID,BALANCE from account_detail) dest',2);
COMMIT;
