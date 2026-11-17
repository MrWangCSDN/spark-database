package cn.sunline.tss.tssveri.spark;

import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DecimalType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves configured fields against the actual JDBC query projection. */
final class QueryFieldResolver {
    private QueryFieldResolver() {}

    static List<Row> resolve(Connection connection,String source,List<Row> fields,String sideIndex,
                             String sideDescription,boolean matchByName) throws SQLException {
        Map<String,Integer> positions=new HashMap<>();
        Set<String> ambiguous=new HashSet<>();
        int columnCount;
        try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT * FROM "+source+" WHERE 1=0")) {
            ResultSetMetaData metadata=result.getMetaData();
            columnCount=metadata.getColumnCount();
            for(int index=1;index<=columnCount;index++) {
                String label=normalized(metadata.getColumnLabel(index));
                if(positions.putIfAbsent(label,index)!=null) ambiguous.add(label);
            }
        }
        if(columnCount==0) throw failure(sideDescription,"","SQL结果没有输出字段");
        if(matchByName && !ambiguous.isEmpty()) {
            String duplicate=ambiguous.stream().sorted().findFirst().orElseThrow();
            throw failure(sideDescription,duplicate,"SQL结果字段名重复");
        }
        Set<String> configuredNames=new HashSet<>();
        Set<Integer> configuredPositions=new HashSet<>();
        List<Row> resolved=new ArrayList<>(fields.size());
        for(Row field:fields) {
            String name=field.getAs("FIELD_NAME");
            String normalizedName=normalized(name);
            if(normalizedName.isEmpty()) throw failure(sideDescription,"","配置字段名为空");
            if(matchByName && !configuredNames.add(normalizedName)) throw failure(sideDescription,name,"配置字段名重复");
            int position;
            if(matchByName) {
                Integer actual=positions.get(normalizedName);
                if(actual==null) throw failure(sideDescription,name,"SQL结果缺少配置字段");
                position=actual;
            } else {
                Object raw=field.getAs(sideIndex);
                try {
                    position=new BigDecimal(String.valueOf(raw)).intValueExact();
                } catch(NumberFormatException | ArithmeticException error) {
                    throw failure(sideDescription,name,"字段位置必须为正整数");
                }
                if(position<1 || position>columnCount) throw failure(sideDescription,name,"字段位置超出SQL结果列范围（1至"+columnCount+"）");
                if(!configuredPositions.add(position)) throw failure(sideDescription,name,"字段位置重复");
            }
            Object[] values=new Object[field.size()];
            for(int index=0;index<values.length;index++) values[index]=field.get(index);
            int positionIndex=field.fieldIndex(sideIndex);
            var positionType=field.schema().fields()[positionIndex].dataType();
            if(positionType.equals(DataTypes.LongType)) values[positionIndex]=(long)position;
            else if(positionType instanceof DecimalType) values[positionIndex]=BigDecimal.valueOf(position);
            else values[positionIndex]=position;
            resolved.add(new GenericRowWithSchema(values,field.schema()));
        }
        return resolved;
    }

    private static String normalized(String name) {
        return name==null?"":name.trim().toLowerCase(Locale.ROOT);
    }

    private static FieldResolutionException failure(String side,String field,String cause) {
        return new FieldResolutionException(side+"字段["+field+"]："+cause);
    }

    static final class FieldResolutionException extends IllegalArgumentException {
        FieldResolutionException(String message) { super(message); }
    }
}
