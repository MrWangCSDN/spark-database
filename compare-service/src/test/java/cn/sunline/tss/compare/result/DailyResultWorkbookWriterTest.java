package cn.sunline.tss.compare.result;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DailyResultWorkbookWriterTest {
    @Test void keepsLongSqlScopesExportableWithAnExplicitCellTruncationMarker() throws Exception {
        String scope="x".repeat(40000);
        var pair=new DailyResultExportData.DatabasePair(1L,"旧库",2L,"新库");
        var data=new DailyResultExportData.DatabaseSection(pair,List.of(),
            List.of(new DailyResultExportData.TableRow(1,"存款组","a_1","—",1,1,0,0,1,"a","配置乙",scope)),
            List.of(new DailyResultExportData.FieldRow(1,"存款组","a_1","—","amount",1,"a","配置乙",scope)));
        try(var workbook=workbook(data)) {
            for(String value:List.of(workbook.getSheetAt(1).getRow(1).getCell(10).getStringCellValue(),
                                    workbook.getSheetAt(2).getRow(1).getCell(7).getStringCellValue())) {
                assertThat(value).hasSize(32767).endsWith("…（查询条件过长，已截断）");
            }
        }
    }
    @Test void writesThreeSheetsWithDynamicHeadersAndNoOldTitleRows() throws Exception {
        var customer=section(1L,"客户库",2L,"历史库","客户","客户表",100,90,12,2,6);
        var core=section(3L,"核心库",4L,"新库","支付","支付表",17,28,4,6,8);
        try(var workbook=workbook(customer,core)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
            assertThat(List.of(workbook.getSheetName(0),workbook.getSheetName(1),workbook.getSheetName(2)))
                .containsExactly("领域汇总","表明细","表字段明细");
            var summary=workbook.getSheetAt(0);var detail=workbook.getSheetAt(1);
            assertThat((Iterable<Cell>)summary.getRow(0)).extracting(Cell::getStringCellValue)
                .containsExactly("领域","客户库总量","历史库总量","仅客户库存在","仅历史库存在","主键相同、字段不同");
            assertThat(summary.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(summary.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(100d);
            assertThat(summary.getRow(1).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(summary.getRow(3).getCell(1).getStringCellValue()).isEqualTo("核心库总量");
            assertThat(summary.getNumMergedRegions()).isZero();
            assertThat(detail.getNumMergedRegions()).isZero();
            assertThat(detail.getRow(0).getCell(1).getStringCellValue()).isEqualTo("表");
            assertThat(detail.getRow(1).getCell(1).getStringCellValue()).isEqualTo("客户表");
            assertThat(summary.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)1);
            assertThat(summary.getTables()).hasSize(2);
            assertThat(summary.getTables().get(0).getStartRowIndex()).isZero();
            assertThat(detail.getTables()).hasSize(2);
            var fields=workbook.getSheetAt(2);
            assertThat(fields.getRow(0).getCell(0).getStringCellValue()).contains("客户库 → 历史库");
            assertThat(fields.getRow(4).getCell(0).getStringCellValue()).contains("核心库 → 新库");
        }
    }
    @Test void writesFullFieldCountsAsNumericCellsWithReviserHeaderAndConfigurationComments() throws Exception {
        var base=section(1L,"来源",2L,"目标","存款组","账户",200000000,200000000,0,0,200000000);
        var data=new DailyResultExportData.DatabaseSection(base.pair(),base.domainRows(),base.tableRows(),List.of(
            new DailyResultExportData.FieldRow(1,"存款组","账户","—","A",260),
            new DailyResultExportData.FieldRow(1,"存款组","账户","—","B",180),
            new DailyResultExportData.FieldRow(1,"存款组","账户","—","LARGE",200000000)));
        try(var workbook=workbook(data)) {
            var sheet=workbook.getSheetAt(2);
            assertThat((Iterable<Cell>)sheet.getRow(0)).extracting(Cell::getStringCellValue).containsExactly("领域","表","负责人","字段名称","差异数量","真实表名","配置名称","查询条件");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("A");
            assertThat(sheet.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(260d);
            assertThat(sheet.getRow(2).getCell(4).getNumericCellValue()).isEqualTo(180d);
            assertThat(sheet.getRow(3).getCell(4).getNumericCellValue()).isEqualTo(200000000d);
            assertThat(sheet.getRow(1).getCell(4).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(1).getCell(1).getCellComment().getString().getString()).contains("配置编号：1");
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)1);
            assertThat(sheet.getTables()).hasSize(1);
            assertThat(sheet.getTables().get(0).getCTTable().getAutoFilter().getRef()).isEqualTo("A1:H4");
        }
    }
    @Test void keepsEmptyFieldSheetHeadersWithoutInventingZeroRows() throws Exception {
        try(var workbook=workbook(section(1L,"来源",2L,"目标","领域","表",0,0,0,0,0))) {
            var sheet=workbook.getSheetAt(2);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(1);
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short)8);
            assertThat(sheet.getTables()).isEmpty();
        }
    }
    @Test void writesFormulaLikeNamesAsStringsAndAddsAFilterTablePerSection() throws Exception {
        var base=section(1L,"=来源库",2L,"+目标库","@领域","-表",1,2,0,1,1);
        var data=new DailyResultExportData.DatabaseSection(base.pair(),base.domainRows(),base.tableRows(),
            List.of(new DailyResultExportData.FieldRow(1,"@领域","-表","—","=SUM(A1)",1)));
        try(var workbook=workbook(data)) {
            for(var sheet:workbook) for(var row:sheet) for(var cell:row) assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("@领域");
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(1).getStringCellValue()).isEqualTo("-表");
            assertThat(workbook.getSheetAt(2).getRow(1).getCell(3).getStringCellValue()).isEqualTo("=SUM(A1)");
            for(int i=0;i<3;i++) assertThat(workbook.getSheetAt(i).getTables().get(0).getCTTable().isSetAutoFilter()).isTrue();
        }
    }
    @Test void rejectsCountsThatExcelCannotRepresentExactly() {
        long unsafe=9_007_199_254_740_993L;
        var individual=section(1L,"来源","目标","表",unsafe);
        var base=section(1L,"来源","目标","表",0);
        var fields=new DailyResultExportData.DatabaseSection(base.pair(),base.domainRows(),base.tableRows(),
            List.of(new DailyResultExportData.FieldRow(1,"领域","表","—","A",unsafe)));
        var aggregate=new DailyResultExportData.DatabaseSection(base.pair(),
            List.of(new DailyResultExportData.DomainRow("领域",unsafe,0,0,0,0)),
            List.of(new DailyResultExportData.TableRow(1,"领域","表1","—",unsafe-1,0,0,0,0),
                new DailyResultExportData.TableRow(2,"领域","表2","—",1,0,0,0,0)),List.of());
        for(var data:List.of(individual,fields,aggregate))
            assertThatThrownBy(()->workbook(data)).hasMessageContaining("Excel精确整数范围");
    }
    @Test void qualifiesCaseInsensitiveDuplicateDatabaseHeadersAndDisambiguatesSameNamedPairs() throws Exception {
        var first=section(1L,"同名库",2L,"同名库","领域","表1",1,1,0,0,1);
        var second=section(3L,"同名库",4L,"同名库","领域","表2",1,1,0,0,1);
        var third=section(5L,"CORE",6L,"core","领域","表3",1,1,0,0,1);
        try(var workbook=workbook(first,second,third)) {
            var summary=workbook.getSheetAt(0);
            assertThat(summary.getRow(0).getCell(1).getStringCellValue()).isEqualTo("来源-同名库总量");
            assertThat(summary.getRow(0).getCell(2).getStringCellValue()).isEqualTo("目标-同名库总量");
            assertThat(summary.getRow(6).getCell(1).getStringCellValue()).isEqualTo("来源-CORE总量");
            assertThat(summary.getRow(6).getCell(2).getStringCellValue()).isEqualTo("目标-core总量");
            assertThat(workbook.getSheetAt(2).getRow(0).getCell(0).getStringCellValue()).contains("1","2");
            assertThat(workbook.getSheetAt(2).getRow(4).getCell(0).getStringCellValue()).contains("3","4");
            assertThat(summary.getTables()).allSatisfy(table->assertThat(table.getCTTable().getTableColumns().getTableColumnList())
                .extracting(column->column.getName().toLowerCase(Locale.ROOT)).doesNotHaveDuplicates());
        }
    }
    private static XSSFWorkbook workbook(DailyResultExportData.DatabaseSection... sections) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(new DailyResultWorkbookWriter().write(new DailyResultExportData(List.of(sections)))));
    }
    private static DailyResultExportData.DatabaseSection section(Long id,String origin,String destination,String table,long count) {
        return section(id,origin,2L,destination,"领域",table,count,count,0,0,count);
    }
    private static DailyResultExportData.DatabaseSection section(Long originId,String origin,Long destinationId,String destination,
                                                               String domain,String table,long count1,long count2,long only1,long only2,long different) {
        return new DailyResultExportData.DatabaseSection(new DailyResultExportData.DatabasePair(originId,origin,destinationId,destination),
            List.of(new DailyResultExportData.DomainRow(domain,count1,count2,only1,only2,different)),
            List.of(new DailyResultExportData.TableRow(originId,domain,table,"—",count1,count2,only1,only2,different)),
            different==0?List.of():List.of(new DailyResultExportData.FieldRow(originId,domain,table,"—","amount",different)));
    }
}
