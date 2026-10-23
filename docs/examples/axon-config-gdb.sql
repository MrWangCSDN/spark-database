-- Axon fixed-format import example for a GDB/MySQL-compatible metadata schema.
-- This file is parsed by bd; execute it through the configuration import API.
START TRANSACTION;
TRUNCATE TABLE TSS_BCOMP_FIELD_NEW;
TRUNCATE TABLE TSS_BCOMP_TABLE_SQL_NEW;
TRUNCATE TABLE TSS_BCOMP_CONF_NEW;
INSERT INTO TSS_BCOMP_CONF_NEW (bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,bcomp_range,bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy) VALUES
(9001,'PAYMENT','GDB订单核对','GDB 两侧复合唯一索引业务键示例','2','ALL','DAY','1',1,'HASH');
INSERT INTO TSS_BCOMP_FIELD_NEW (bcomp_index,field_name,field_type,field_memo,field_old_index,field_new_index,field_index_flag,field_enum_flag) VALUES
(9001,'TENANT_ID','1','租户号',1,1,'1','0'),
(9001,'ORDER_ID','1','订单号',2,2,'1','0'),
(9001,'AMOUNT','2','金额',3,3,'0','0');
INSERT INTO TSS_BCOMP_TABLE_SQL_NEW (bcomp_index,orig_sql,orig_database_id,dest_sql,dest_database_id) VALUES
(9001,'SELECT TENANT_ID,ORDER_ID,AMOUNT FROM PAYMENT_ORDER WHERE BIZ_DATE = ''${HDRQ}''',1,'SELECT TENANT_ID,ORDER_ID,AMOUNT FROM PAYMENT_ORDER WHERE BIZ_DATE = ''${HDRQ}''',2);
COMMIT;
