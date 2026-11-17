package cn.sunline.tss.tssveri.spark;

import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QueryFieldResolverTest {
    private static Row field(String name,Integer oldPosition,Integer newPosition) {
        StructType schema=new StructType().add("FIELD_NAME",DataTypes.StringType)
            .add("FIELD_OLD_INDEX",DataTypes.IntegerType).add("FIELD_NEW_INDEX",DataTypes.IntegerType)
            .add("FIELD_INDEX_FLAG",DataTypes.StringType).add("FIELD_MEMO",DataTypes.StringType);
        return new GenericRowWithSchema(new Object[]{name,oldPosition,newPosition,"1","中文说明"},schema);
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID(),"sa","");
    }

    @Test void namesOverrideStalePositionsAndResolveEachSideIndependently() throws Exception {
        try(var connection=connection()) {
            var original=List.of(field("card_tp_id",37,37),field("Amount",0,null));
            var origin=QueryFieldResolver.resolve(connection,
                "(SELECT 10 AS amount,20 AS other,30 AS CARD_TP_ID) q",original,"FIELD_OLD_INDEX","数据库A",true);
            var both=QueryFieldResolver.resolve(connection,
                "(SELECT 30 AS card_tp_id,10 AS AMOUNT) q",origin,"FIELD_NEW_INDEX","数据库B",true);
            assertEquals(3,((Number)both.get(0).getAs("FIELD_OLD_INDEX")).intValue());
            assertEquals(1,((Number)both.get(0).getAs("FIELD_NEW_INDEX")).intValue());
            assertEquals(1,((Number)both.get(1).getAs("FIELD_OLD_INDEX")).intValue());
            assertEquals(2,((Number)both.get(1).getAs("FIELD_NEW_INDEX")).intValue());
            assertEquals(37,((Number)original.get(0).getAs("FIELD_OLD_INDEX")).intValue());
            assertNull(original.get(1).getAs("FIELD_NEW_INDEX"));
            assertEquals("中文说明",both.get(0).getAs("FIELD_MEMO"));
            assertEquals("1",both.get(0).getAs("FIELD_INDEX_FLAG"));
            assertEquals("card_tp_id",both.get(0).getAs("FIELD_NAME"));
        }
    }

    @Test void matchesTrimmedCaseInsensitiveOutputAliases() throws Exception {
        try(var connection=connection()) {
            var resolved=QueryFieldResolver.resolve(connection,"(SELECT 1 AS \"Total_Amount\") q",
                List.of(field(" total_AMOUNT ",null,null)),"FIELD_OLD_INDEX","数据库A",true);
            assertEquals(1,((Number)resolved.get(0).getAs("FIELD_OLD_INDEX")).intValue());
        }
    }

    @Test void resolvedValuesKeepLongAndDecimalSchemaRepresentations() throws Exception {
        try(var connection=connection()) {
            StructType schema=new StructType().add("FIELD_NAME",DataTypes.StringType)
                .add("FIELD_OLD_INDEX",DataTypes.LongType).add("FIELD_NEW_INDEX",DataTypes.createDecimalType(20,0));
            Row original=new GenericRowWithSchema(new Object[]{"id",37L,new java.math.BigDecimal("37")},schema);
            var origin=QueryFieldResolver.resolve(connection,"(SELECT 1 AS id) q",List.of(original),"FIELD_OLD_INDEX","数据库A",true);
            assertEquals(1L,origin.get(0).getLong(1));
            assertEquals(new java.math.BigDecimal("37"),origin.get(0).getDecimal(2));
            var both=QueryFieldResolver.resolve(connection,"(SELECT 1 AS other,2 AS id) q",origin,"FIELD_NEW_INDEX","数据库B",true);
            assertEquals(1L,both.get(0).getLong(1));
            assertEquals(new java.math.BigDecimal("2"),both.get(0).getDecimal(2));
            assertEquals(schema,both.get(0).schema());
        }
    }

    @Test void missingNameDoesNotFallBackToValidStoredPosition() throws Exception {
        try(var connection=connection()) {
            var error=assertThrows(QueryFieldResolver.FieldResolutionException.class,()->QueryFieldResolver.resolve(
                connection,"(SELECT 1 AS actual) q",List.of(field("missing",1,1)),"FIELD_OLD_INDEX","数据库A",true));
            assertTrue(error.getMessage().contains("数据库A"));
            assertTrue(error.getMessage().contains("missing"));
            assertTrue(error.getMessage().contains("缺少"));
            assertFalse(error.getMessage().contains("SELECT"));
        }
    }

    @Test void rejectsCaseInsensitiveDuplicateOutputLabelsAndConfigurationNames() throws Exception {
        try(var connection=connection()) {
            var repeated=assertThrows(QueryFieldResolver.FieldResolutionException.class,()->QueryFieldResolver.resolve(
                connection,"(SELECT 1 AS \"ID\",2 AS \"id\") q",List.of(field("id",1,1)),"FIELD_OLD_INDEX","数据库A",true));
            assertTrue(repeated.getMessage().contains("SQL结果字段名重复"));
            var configured=assertThrows(QueryFieldResolver.FieldResolutionException.class,()->QueryFieldResolver.resolve(
                connection,"(SELECT 1 AS id) q",List.of(field("ID",1,1),field(" id ",2,2)),"FIELD_NEW_INDEX","数据库B",true));
            assertTrue(configured.getMessage().contains("配置字段名重复"));
        }
    }

    @Test void rejectsEmptyResultMetadata() throws Exception {
        var metadata=stub(java.sql.ResultSetMetaData.class,java.util.Map.of("getColumnCount",0));
        var result=stub(java.sql.ResultSet.class,java.util.Map.of("getMetaData",metadata));
        var statement=stub(java.sql.Statement.class,java.util.Map.of("executeQuery",result));
        var connection=stub(Connection.class,java.util.Map.of("createStatement",statement));
        var error=assertThrows(QueryFieldResolver.FieldResolutionException.class,()->QueryFieldResolver.resolve(
            connection,"unused",List.of(field("id",1,1)),"FIELD_OLD_INDEX","数据库A",true));
        assertTrue(error.getMessage().contains("没有输出字段"));
    }

    private static <T> T stub(Class<T> type,java.util.Map<String,Object> returns) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},
            (proxy,method,args)->returns.get(method.getName())));
    }

    @Test void positionModeKeepsExplicitMappingButRejectsOutOfRangeAndRepeatedPositions() throws Exception {
        try(var connection=connection()) {
            var resolved=QueryFieldResolver.resolve(connection,"(SELECT 1 AS other,2 AS val) q",
                List.of(field("legacy_name",2,37)),"FIELD_OLD_INDEX","数据库A",false);
            assertEquals(2,((Number)resolved.get(0).getAs("FIELD_OLD_INDEX")).intValue());
            assertEquals(37,((Number)resolved.get(0).getAs("FIELD_NEW_INDEX")).intValue());
            var error=assertThrows(QueryFieldResolver.FieldResolutionException.class,()->QueryFieldResolver.resolve(
                connection,"(SELECT 1 AS id) q",List.of(field("card_tp_id",37,37)),"FIELD_OLD_INDEX","数据库A",false));
            assertTrue(error.getMessage().contains("card_tp_id"));
            assertTrue(error.getMessage().contains("1至1"));
            assertThrows(QueryFieldResolver.FieldResolutionException.class,()->QueryFieldResolver.resolve(
                connection,"(SELECT 1 AS id,2 AS val) q",List.of(field("id",1,1),field("val",1,2)),"FIELD_OLD_INDEX","数据库A",false));
            var repeatedNames=QueryFieldResolver.resolve(connection,"(SELECT 1 AS id,2 AS val) q",
                List.of(field("legacy",1,1),field("legacy",2,2)),"FIELD_OLD_INDEX","数据库A",false);
            assertEquals(2,repeatedNames.size());
            for(Integer invalid:new Integer[]{null,0,-1}) {
                assertThrows(QueryFieldResolver.FieldResolutionException.class,()->QueryFieldResolver.resolve(
                    connection,"(SELECT 1 AS id) q",List.of(field("id",invalid,1)),"FIELD_OLD_INDEX","数据库A",false));
            }
        }
    }
}
