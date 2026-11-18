package cn.sunline.tss.compare.result;

import cn.sunline.tss.compare.api.ApiException;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DailyResultExportProjectionTest {
    @Test void mapsAllDomainCodesAndPreservesUnknownNames() {
        var rows=new java.util.ArrayList<DailyResultSummary>();
        var domains=List.of("dept"," DEPT ","loan","COMM","sett","存款组","  custom  "," ");
        for(int i=0;i<domains.size();i++) rows.add(result(i+1,"表"+i,domains.get(i),1L,"来源",2L,"目标",1,1,0,0,1));
        var data=build(rows).sections().get(0);
        assertThat(data.tableRows()).extracting(DailyResultExportData.TableRow::domain)
            .containsOnly("存款组","贷款组","公共组","结算组","  custom  ","未分组");
        assertThat(data.domainRows().stream().filter(row->row.domain().equals("存款组")).findFirst().orElseThrow().differentCount()).isEqualTo(3);
        assertThat(data.fieldRows()).extracting(DailyResultExportData.FieldRow::domain)
            .containsOnly("存款组","贷款组","公共组","结算组","  custom  ","未分组");
    }
    @Test void sortsFieldRowsDescendingWithoutMergingSameNamedConfigurations() {
        var snapshot=new DailyResultExportSnapshot(List.of(
            new DailyResultExportSnapshot.TableResult(result(3,"Z表","dept",1L,"来源",2L,"目标",260,260,0,0,260),Map.of("C",260L,"A",260L)),
            new DailyResultExportSnapshot.TableResult(result(1,"Z表","dept",1L,"来源",2L,"目标",260,260,0,0,260),Map.of("A",260L,"B",180L)),
            new DailyResultExportSnapshot.TableResult(result(4,"A表","dept",1L,"来源",2L,"目标",10,10,0,0,10),Map.of("C",10L)),
            new DailyResultExportSnapshot.TableResult(result(2,"B表","loan",1L,"来源",2L,"目标",1,1,0,0,1),Map.of("X",1L))));
        var section=DailyResultExportProjection.build(snapshot).sections().get(0);
        assertThat(section.fieldRows()).extracting(row->row.domain()+"/"+row.tableName()+"/"+row.fieldName()+"/"+row.differentCount()+"/"+row.bcompIndex())
            .containsExactly("贷款组/B表/X/1/2","存款组/Z表/A/260/1","存款组/Z表/A/260/3","存款组/Z表/C/260/3","存款组/Z表/B/180/1","存款组/A表/C/10/4");
        assertThat(section.tableRows()).hasSize(4);
        assertThat(section.domainRows().stream().filter(row->row.domain().equals("存款组")).findFirst().orElseThrow().differentCount()).isEqualTo(530);
        assertThat(build(List.of(result(5,"无差异","dept",1L,"来源",2L,"目标",0,0,0,0,0))).sections().get(0).fieldRows()).isEmpty();
    }
    private static DailyResultExportData build(List<DailyResultSummary> rows) {
        return DailyResultExportProjection.build(new DailyResultExportSnapshot(rows.stream().map(row->
            new DailyResultExportSnapshot.TableResult(row,row.differentCount()==0?Map.<String,Long>of():Map.of("A",row.differentCount()))).toList()));
    }
    @Test void groupsDatabasePairsAndAggregatesDomainsWithStableTableOrdering() {
        var data=build(List.of(
            result(30,"B表","支付",3L,"核心库",4L,"新库",10,20,3,4,5),
            result(20,"A表","支付",3L,"核心库",4L,"新库",7,8,1,2,3),
            result(10,"客户表","客户",1L,"客户库",2L,"历史库",100,90,12,2,6)
        ));

        assertThat(data.sections()).hasSize(2);
        var first=data.sections().get(0);
        assertThat(first.pair().originDatabaseName()).isEqualTo("客户库");
        assertThat(first.pair().destinationDatabaseName()).isEqualTo("历史库");
        assertThat(first.domainRows()).extracting(DailyResultExportData.DomainRow::domain).containsExactly("客户");
        assertThat(first.tableRows()).extracting(DailyResultExportData.TableRow::tableName).containsExactly("客户表");

        var second=data.sections().get(1);
        assertThat(second.pair().originDatabaseName()).isEqualTo("核心库");
        assertThat(second.domainRows()).containsExactly(
            new DailyResultExportData.DomainRow("支付",17,28,4,6,8)
        );
        assertThat(second.tableRows()).extracting(DailyResultExportData.TableRow::tableName).containsExactly("A表","B表");
        assertThat(second.tableRows()).extracting(DailyResultExportData.TableRow::originCount).containsExactly(7L,10L);
    }

    @Test void keepsSameNamedPairsWithDifferentIdsSeparateAndMapsBlankDomain() {
        var data=build(List.of(
            result(2,"表二","  ",11L,"共享库",12L,"目标库",2,3,1,0,1),
            result(1,"表一",null,1L,"共享库",2L,"目标库",5,6,0,1,2)
        ));

        assertThat(data.sections()).hasSize(2);
        assertThat(data.sections()).extracting(section->section.pair().originDatabaseId()).containsExactly(1L,11L);
        assertThat(data.sections()).allSatisfy(section->
            assertThat(section.domainRows()).extracting(DailyResultExportData.DomainRow::domain).containsExactly("未分组"));
    }

    @Test void rejectsBlankSavedNamesAndExactAdditionOverflow() {
        assertExportFailure(List.of(result(1,"表一","领域",1L," ",2L,"目标库",1,1,0,0,0)))
            .hasMessageContaining("数据库名称");
        assertExportFailure(List.of(result(1," ","领域",1L,"来源库",2L,"目标库",1,1,0,0,0)))
            .hasMessageContaining("表名");

        var overflow=catchThrowableOfType(() -> build(List.of(
            result(1,"表一","领域",1L,"来源库",2L,"目标库",Long.MAX_VALUE,1,0,0,0),
            result(2,"表二","领域",1L,"来源库",2L,"目标库",1,1,0,0,0)
        )),ApiException.class);
        assertThat(overflow.code()).isEqualTo("RESULT_EXPORT_FAILED");
        assertThat(overflow.status().value()).isEqualTo(500);
        assertThat(overflow).hasCauseInstanceOf(ArithmeticException.class);
    }

    private static AbstractThrowableAssert<?,? extends Throwable> assertExportFailure(List<DailyResultSummary> rows) {
        return assertThatThrownBy(()->build(rows))
            .isInstanceOf(ApiException.class)
            .satisfies(error->assertThat(((ApiException)error).code()).isEqualTo("RESULT_EXPORT_FAILED"));
    }

    private static DailyResultSummary result(long index,String table,String domain,Long originId,String originName,
                                             Long destinationId,String destinationName,long origin,long destination,
                                             long onlyOrigin,long onlyDestination,long different) {
        return new DailyResultSummary(index,"20260927","run-"+index,"20260927","batch","D",table,domain,null,null,
            originId,originName,destinationId,destinationName,origin,destination,0,onlyOrigin,onlyDestination,different,
            100,0,0,0,"20260927120000","0");
    }
}
