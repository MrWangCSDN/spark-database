package cn.sunline.tss.compare.configuration;

public record ResultCleanupSummary(int retentionDays,String cutoff,long deletedResults,long deletedDetails,
                                   long deletedFieldPatterns,long deletedFieldCounts,long deletedLegacyDetails) {}
