package cn.sunline.tss.compare.api;

public record ApiError(String code, String message, String traceId) {}
