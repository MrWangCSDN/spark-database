package cn.sunline.tss.compare.result;

import cn.sunline.tss.compare.api.ApiException;

import java.util.*;

final class DailyResultExportProjection {
    private static final String UNGROUPED="未分组";
    private static final Comparator<String> TEXT=Comparator.nullsFirst(Comparator.naturalOrder());
    private static final Comparator<Long> IDS=Comparator.nullsFirst(Comparator.naturalOrder());
    private static final Comparator<DailyResultExportData.DatabasePair> PAIRS=
        Comparator.comparing(DailyResultExportData.DatabasePair::originDatabaseName,TEXT)
            .thenComparing(DailyResultExportData.DatabasePair::destinationDatabaseName,TEXT)
            .thenComparing(DailyResultExportData.DatabasePair::originDatabaseId,IDS)
            .thenComparing(DailyResultExportData.DatabasePair::destinationDatabaseId,IDS);

    private DailyResultExportProjection() {}

    static DailyResultExportData build(DailyResultExportSnapshot snapshot) {
        try {
            var groups=new TreeMap<DailyResultExportData.DatabasePair,List<DailyResultExportSnapshot.TableResult>>(PAIRS);
            for(var item:snapshot.results()) {
                var result=item.summary();
                requireText(result.originDatabaseName(),"来源数据库名称缺失");
                requireText(result.destinationDatabaseName(),"目标数据库名称缺失");
                requireText(result.configurationName(),"表名缺失");
                var pair=new DailyResultExportData.DatabasePair(result.originDatabaseId(),result.originDatabaseName(),
                    result.destinationDatabaseId(),result.destinationDatabaseName());
                groups.computeIfAbsent(pair,ignored->new ArrayList<>()).add(item);
            }
            var sections=new ArrayList<DailyResultExportData.DatabaseSection>();
            groups.forEach((pair,rows)->sections.add(section(pair,rows)));
            return new DailyResultExportData(sections);
        } catch(ApiException error) {
            throw error;
        } catch(ArithmeticException error) {
            throw ApiException.internal("RESULT_EXPORT_FAILED","报表数量汇总超出支持范围",error);
        } catch(RuntimeException error) {
            throw ApiException.internal("RESULT_EXPORT_FAILED","每日核对结果报表数据不完整",error);
        }
    }

    private static DailyResultExportData.DatabaseSection section(DailyResultExportData.DatabasePair pair,List<DailyResultExportSnapshot.TableResult> rows) {
        var domains=new TreeMap<String,Totals>();
        var tableRows=new ArrayList<DailyResultExportData.TableRow>();
        var fieldRows=new ArrayList<DailyResultExportData.FieldRow>();
        for(var item:rows) {
            var result=item.summary();
            String domain=domain(result.configurationModule());
            String reviser=reviser(result.reviserName(),result.reviserUsername());
            domains.computeIfAbsent(domain,ignored->new Totals()).add(result);
            tableRows.add(new DailyResultExportData.TableRow(result.bcompIndex(),domain,result.configurationName(),reviser,
                result.originCount(),result.destinationCount(),result.onlyOriginCount(),result.onlyDestinationCount(),result.differentCount()));
            item.fieldCounts().forEach((field,count)->fieldRows.add(new DailyResultExportData.FieldRow(
                result.bcompIndex(),domain,result.configurationName(),reviser,field,count)));
        }
        tableRows.sort(Comparator.comparing(DailyResultExportData.TableRow::domain)
            .thenComparing(DailyResultExportData.TableRow::tableName)
            .thenComparingLong(DailyResultExportData.TableRow::bcompIndex));
        var domainRows=domains.entrySet().stream().map(entry->entry.getValue().domain(entry.getKey())).toList();
        fieldRows.sort(Comparator.comparing(DailyResultExportData.FieldRow::domain,Comparator.reverseOrder())
            .thenComparing(DailyResultExportData.FieldRow::tableName,Comparator.reverseOrder())
            .thenComparing(Comparator.comparingLong(DailyResultExportData.FieldRow::differentCount).reversed())
            .thenComparing(DailyResultExportData.FieldRow::fieldName)
            .thenComparingLong(DailyResultExportData.FieldRow::bcompIndex));
        verify(domainRows,tableRows);
        return new DailyResultExportData.DatabaseSection(pair,domainRows,tableRows,fieldRows);
    }

    private static void verify(List<DailyResultExportData.DomainRow> domains,List<DailyResultExportData.TableRow> tables) {
        var domainTotals=new Totals();
        for(var row:domains) domainTotals.add(row.originCount(),row.destinationCount(),row.onlyOriginCount(),row.onlyDestinationCount(),row.differentCount());
        var tableTotals=new Totals();
        for(var row:tables) tableTotals.add(row.originCount(),row.destinationCount(),row.onlyOriginCount(),row.onlyDestinationCount(),row.differentCount());
        if(!domainTotals.equals(tableTotals)) throw ApiException.internal("RESULT_EXPORT_FAILED","领域汇总与表明细不一致",null);
    }

    private static String domain(String value) {
        if(value==null||value.isBlank()) return UNGROUPED;
        return switch(value.trim().toLowerCase(Locale.ROOT)) {
            case "dept" -> "存款组";
            case "loan" -> "贷款组";
            case "comm" -> "公共组";
            case "sett" -> "结算组";
            default -> value;
        };
    }
    private static String reviser(String name,String username) {
        String displayName=name==null?"":name.trim();
        String account=username==null?"":username.trim();
        if(!displayName.isEmpty()&&!account.isEmpty()) return displayName+"("+account+")";
        if(!displayName.isEmpty()) return displayName;
        return account.isEmpty()?"—":account;
    }
    private static void requireText(String value,String message) {
        if(value==null||value.isBlank()) throw ApiException.internal("RESULT_EXPORT_FAILED",message,null);
    }

    private static final class Totals {
        long origin,destination,onlyOrigin,onlyDestination,different;
        void add(DailyResultSummary row) {
            add(row.originCount(),row.destinationCount(),row.onlyOriginCount(),row.onlyDestinationCount(),row.differentCount());
        }
        void add(long origin,long destination,long onlyOrigin,long onlyDestination,long different) {
            this.origin=Math.addExact(this.origin,origin);
            this.destination=Math.addExact(this.destination,destination);
            this.onlyOrigin=Math.addExact(this.onlyOrigin,onlyOrigin);
            this.onlyDestination=Math.addExact(this.onlyDestination,onlyDestination);
            this.different=Math.addExact(this.different,different);
        }
        DailyResultExportData.DomainRow domain(String name) {
            return new DailyResultExportData.DomainRow(name,origin,destination,onlyOrigin,onlyDestination,different);
        }
        @Override public boolean equals(Object other) {
            return other instanceof Totals value && origin==value.origin && destination==value.destination
                && onlyOrigin==value.onlyOrigin && onlyDestination==value.onlyDestination && different==value.different;
        }
        @Override public int hashCode() { return Objects.hash(origin,destination,onlyOrigin,onlyDestination,different); }
    }
}
