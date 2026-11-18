package cn.sunline.tss.compare.result;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.*;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTable;
import org.springframework.stereotype.Component;

import java.io.*;

@Component
public class DailyResultWorkbookWriter {
    private static final long MAX_EXACT_EXCEL_INTEGER=9_007_199_254_740_992L;
    private static final String[] SUMMARY_FIXED={"领域"};
    private static final String[] DETAIL_FIXED={"领域","表","负责人"};
    private static final String[] FIELD_HEADERS={"领域","表","负责人","字段名称","差异数量"};

    public byte[] write(DailyResultExportData data) {
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var styles=new Styles(workbook);
            writeSummary(workbook,data,styles);
            writeDetails(workbook,data,styles);
            writeFields(workbook,data,styles);
            workbook.write(output);
            return output.toByteArray();
        } catch(IOException error) {
            throw new IllegalStateException("生成每日核对结果工作簿失败",error);
        }
    }

    private static void writeSummary(XSSFWorkbook workbook,DailyResultExportData data,Styles styles) {
        var sheet=workbook.createSheet("领域汇总");
        int rowIndex=0,sectionNumber=0;
        for(var section:data.sections()) {
            int headerRow=rowIndex++;
            headers(sheet,headerRow,SUMMARY_FIXED,section.pair(),styles.header);
            for(var item:section.domainRows()) {
                var row=sheet.createRow(rowIndex++);
                text(row,0,item.domain(),styles.text);
                numbers(row,1,item.originCount(),item.destinationCount(),item.onlyOriginCount(),item.onlyDestinationCount(),item.differentCount(),styles.number);
            }
            table(sheet,headerRow,rowIndex-1,5,"DomainSection"+(++sectionNumber));
            rowIndex++;
        }
        sheet.createFreezePane(0,1);
        widths(sheet,false);
    }

    private static void writeDetails(XSSFWorkbook workbook,DailyResultExportData data,Styles styles) {
        var sheet=workbook.createSheet("表明细");
        int rowIndex=0,sectionNumber=0;
        for(var section:data.sections()) {
            int headerRow=rowIndex++;
            headers(sheet,headerRow,DETAIL_FIXED,section.pair(),styles.header);
            for(var item:section.tableRows()) {
                var row=sheet.createRow(rowIndex++);
                text(row,0,item.domain(),styles.text);
                text(row,1,item.tableName(),styles.text);
                text(row,2,item.reviser(),styles.text);
                numbers(row,3,item.originCount(),item.destinationCount(),item.onlyOriginCount(),item.onlyDestinationCount(),item.differentCount(),styles.number);
            }
            table(sheet,headerRow,rowIndex-1,7,"TableSection"+(++sectionNumber));
            rowIndex++;
        }
        sheet.createFreezePane(0,1);
        widths(sheet,true);
    }

    private static void writeFields(XSSFWorkbook workbook,DailyResultExportData data,Styles styles) {
        var sheet=workbook.createSheet("表字段明细");
        var drawing=sheet.createDrawingPatriarch();
        var helper=workbook.getCreationHelper();
        boolean multiplePairs=data.sections().size()>1;
        int rowIndex=0,sectionNumber=0;
        for(var section:data.sections()) {
            if(multiplePairs) {
                var pair=section.pair();
                boolean sameNamedPair=data.sections().stream().filter(other->
                    other.pair().originDatabaseName().equalsIgnoreCase(pair.originDatabaseName()) &&
                    other.pair().destinationDatabaseName().equalsIgnoreCase(pair.destinationDatabaseName())).count()>1;
                String label=pair.originDatabaseName()+" → "+pair.destinationDatabaseName();
                if(sameNamedPair) label+="（数据库编号："+pair.originDatabaseId()+" → "+pair.destinationDatabaseId()+"）";
                title(sheet,rowIndex++,4,label,styles.title);
            }
            int headerRow=rowIndex++;
            var header=sheet.createRow(headerRow);
            for(int column=0;column<FIELD_HEADERS.length;column++) text(header,column,FIELD_HEADERS[column],styles.header);
            for(var item:section.fieldRows()) {
                var row=sheet.createRow(rowIndex++);
                text(row,0,item.domain(),styles.text);
                text(row,1,item.tableName(),styles.text);
                text(row,2,item.reviser(),styles.text);
                text(row,3,item.fieldName(),styles.text);
                number(row,4,item.differentCount(),styles.number);
                var anchor=helper.createClientAnchor();
                anchor.setCol1(1);anchor.setCol2(5);anchor.setRow1(row.getRowNum());anchor.setRow2(row.getRowNum()+2);
                var comment=drawing.createCellComment(anchor);
                comment.setAuthor("核对报表");
                comment.setString(helper.createRichTextString("配置编号："+item.bcompIndex()));
                row.getCell(1).setCellComment(comment);
            }
            table(sheet,headerRow,rowIndex-1,4,"FieldSection"+(++sectionNumber));
            rowIndex++;
        }
        sheet.createFreezePane(0,multiplePairs?2:1);
        sheet.setColumnWidth(0,22*256);
        sheet.setColumnWidth(1,30*256);
        sheet.setColumnWidth(2,28*256);
        sheet.setColumnWidth(3,32*256);
        sheet.setColumnWidth(4,20*256);
    }

    private static void title(XSSFSheet sheet,int rowIndex,int lastColumn,String label,CellStyle style) {
        var row=sheet.createRow(rowIndex);
        text(row,0,label,style);
        for(int column=1;column<=lastColumn;column++) row.createCell(column).setCellStyle(style);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex,rowIndex,0,lastColumn));
    }

    private static void headers(XSSFSheet sheet,int rowIndex,String[] fixed,DailyResultExportData.DatabasePair pair,CellStyle style) {
        var row=sheet.createRow(rowIndex);int column=0;
        for(String value:fixed) text(row,column++,value,style);
        boolean duplicate=pair.originDatabaseName().equalsIgnoreCase(pair.destinationDatabaseName());
        String origin=(duplicate?"来源-":"")+pair.originDatabaseName();
        String destination=(duplicate?"目标-":"")+pair.destinationDatabaseName();
        text(row,column++,origin+"总量",style);
        text(row,column++,destination+"总量",style);
        text(row,column++,"仅"+origin+"存在",style);
        text(row,column++,"仅"+destination+"存在",style);
        text(row,column,"主键相同、字段不同",style);
    }

    private static void text(Row row,int column,String value,CellStyle style) {
        var cell=row.createCell(column,CellType.STRING);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void numbers(Row row,int start,long origin,long destination,long onlyOrigin,long onlyDestination,long different,CellStyle style) {
        long[] values={origin,destination,onlyOrigin,onlyDestination,different};
        for(int index=0;index<values.length;index++) {
            number(row,start+index,values[index],style);
        }
    }

    private static void number(Row row,int column,long value,CellStyle style) {
        if(value<-MAX_EXACT_EXCEL_INTEGER||value>MAX_EXACT_EXCEL_INTEGER)
            throw new IllegalArgumentException("报表数量超出Excel精确整数范围");
        var cell=row.createCell(column,CellType.NUMERIC);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void table(XSSFSheet sheet,int headerRow,int lastDataRow,int lastColumn,String name) {
        if(lastDataRow<headerRow+1) return;
        var area=new AreaReference(new CellReference(headerRow,0),new CellReference(lastDataRow,lastColumn),SpreadsheetVersion.EXCEL2007);
        var table=sheet.createTable(area);
        table.setName(name);
        table.setDisplayName(name);
        table.setStyleName("TableStyleMedium2");
        CTTable definition=table.getCTTable();
        if(!definition.isSetAutoFilter()) definition.addNewAutoFilter();
        definition.getAutoFilter().setRef(area.formatAsString());
    }

    private static void widths(XSSFSheet sheet,boolean details) {
        sheet.setColumnWidth(0,22*256);
        int metricStart=1;
        if(details) {sheet.setColumnWidth(1,30*256);sheet.setColumnWidth(2,28*256);metricStart=3;}
        for(int column=metricStart;column<metricStart+4;column++) sheet.setColumnWidth(column,18*256);
        sheet.setColumnWidth(metricStart+4,24*256);
    }

    private static final class Styles {
        final CellStyle title,header,text,number;
        Styles(XSSFWorkbook workbook) {
            var titleFont=workbook.createFont();titleFont.setBold(true);titleFont.setFontHeightInPoints((short)14);
            title=workbook.createCellStyle();title.setFont(titleFont);title.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());title.setFillPattern(FillPatternType.SOLID_FOREGROUND);title.setFont(white(workbook,titleFont));
            var headerFont=workbook.createFont();headerFont.setBold(true);headerFont.setColor(IndexedColors.WHITE.getIndex());
            header=workbook.createCellStyle();header.setFont(headerFont);header.setFillForegroundColor(IndexedColors.BLUE_GREY.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);header.setWrapText(true);
            text=workbook.createCellStyle();
            number=workbook.createCellStyle();number.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
        }
        private static Font white(XSSFWorkbook workbook,Font source) {
            var font=workbook.createFont();font.setBold(source.getBold());font.setFontHeightInPoints(source.getFontHeightInPoints());font.setColor(IndexedColors.WHITE.getIndex());return font;
        }
    }
}
