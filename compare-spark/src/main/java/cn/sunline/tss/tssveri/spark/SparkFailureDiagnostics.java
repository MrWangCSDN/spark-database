package cn.sunline.tss.tssveri.spark;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded failure categories without JDBC messages, SQL or row contents. */
final class SparkFailureDiagnostics {
    private static final int SUMMARY_LIMIT=500;
    private static final int CAUSE_LIMIT=8;
    private static final int SUPPRESSED_LIMIT=3;
    private static final Pattern DISK_FULL=Pattern.compile("(?i)(?:^|[\\s:(])(?:ENOSPC|No space left on device)(?=$|[\\s):.,])");

    private SparkFailureDiagnostics() {}

    static String summary(Throwable failure) {
        if(failure==null) return "UnknownFailure";
        var causes=causeChain(failure);
        var value=new StringBuilder();
        appendChain(value,causes,true);
        int additionalCount=0;
        for(Throwable cause:causes) {
            for(Throwable additional:cause.getSuppressed()) {
                if(additionalCount++>=SUPPRESSED_LIMIT) return bounded(value);
                value.append("；附加异常 ");
                appendChain(value,causeChain(additional),false);
            }
        }
        return bounded(value);
    }

    private static List<Throwable> causeChain(Throwable failure) {
        var causes=new ArrayList<Throwable>();
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current=failure;
        while(current!=null&&causes.size()<CAUSE_LIMIT&&seen.add(current)) {
            causes.add(current);
            current=current.getCause();
        }
        return causes;
    }

    private static void appendChain(StringBuilder value,List<Throwable> causes,boolean includeFieldMessage) {
        boolean first=true;
        for(Throwable cause:causes) {
            if(!first) value.append(" -> ");
            first=false;
            value.append(cause.getClass().getSimpleName());
            if(includeFieldMessage&&cause instanceof QueryFieldResolver.FieldResolutionException) {
                String message=cause.getMessage();
                if(message!=null) value.append("（").append(message.replace('\n',' ').replace('\r',' ')).append("）");
            } else if(cause instanceof OutOfMemoryError) {
                value.append(" [OUT_OF_MEMORY 内存不足]");
            } else if(cause instanceof ArrayIndexOutOfBoundsException) {
                value.append(" [ARRAY_INDEX_OUT_OF_RANGE 数组下标越界]");
            } else if(cause instanceof IOException ioFailure&&isDiskFull(ioFailure)) {
                value.append(" [LOCAL_DISK_FULL 本地磁盘空间不足]");
            }
        }
    }

    private static boolean isDiskFull(IOException failure) {
        String reason=failure instanceof FileSystemException fileFailure?fileFailure.getReason():failure.getMessage();
        return reason!=null&&DISK_FULL.matcher(reason).find();
    }

    private static String bounded(StringBuilder value) {
        if(value.length()<=SUMMARY_LIMIT) return value.toString();
        return value.substring(0,SUMMARY_LIMIT-1)+"…";
    }
}
