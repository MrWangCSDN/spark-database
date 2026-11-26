package cn.sunline.tss.compare.result;

import java.util.List;

public record DailyResultExportData(List<DatabaseSection> sections) {
    public DailyResultExportData { sections=List.copyOf(sections); }

    public record DatabasePair(Long originDatabaseId,String originDatabaseName,
                               Long destinationDatabaseId,String destinationDatabaseName) {}

    public record DatabaseSection(DatabasePair pair,List<DomainRow> domainRows,List<TableRow> tableRows,List<FieldRow> fieldRows) {
        public DatabaseSection {
            domainRows=List.copyOf(domainRows);
            tableRows=List.copyOf(tableRows);
            fieldRows=List.copyOf(fieldRows);
        }
    }

    public record DomainRow(String domain,long originCount,long destinationCount,long onlyOriginCount,
                            long onlyDestinationCount,long differentCount) {}

    public record TableRow(long bcompIndex,String domain,String tableName,String reviser,long originCount,long destinationCount,
                           long onlyOriginCount,long onlyDestinationCount,long differentCount,String sourceTableName,String configurationName,String configurationScope) {
        public TableRow(long bcompIndex,String domain,String tableName,String reviser,long originCount,long destinationCount,long onlyOriginCount,long onlyDestinationCount,long differentCount) {
            this(bcompIndex,domain,tableName,reviser,originCount,destinationCount,onlyOriginCount,onlyDestinationCount,differentCount,null,null,null);
        }
    }

    public record FieldRow(long bcompIndex,String domain,String tableName,String reviser,String fieldName,long differentCount,String sourceTableName,String configurationName,String configurationScope) {
        public FieldRow(long bcompIndex,String domain,String tableName,String reviser,String fieldName,long differentCount) {
            this(bcompIndex,domain,tableName,reviser,fieldName,differentCount,null,null,null);
        }
    }
}
