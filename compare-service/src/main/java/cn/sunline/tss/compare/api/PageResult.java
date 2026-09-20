package cn.sunline.tss.compare.api;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long total) {
    public PageResult {
        items = List.copyOf(items);
        if (page < 0 || size < 1 || size > 100 || total < 0) {
            throw new IllegalArgumentException("分页参数不合法");
        }
    }
}
