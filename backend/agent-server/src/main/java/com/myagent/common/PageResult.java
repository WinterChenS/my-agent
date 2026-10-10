package com.myagent.common;

import java.util.List;

/**
 * 分页响应：{@code {"records":[...],"total":n,"current":n,"size":n}}。
 *
 * @param <T> 记录类型
 */
public record PageResult<T>(List<T> records, long total, long current, long size) {

    public static <T> PageResult<T> of(List<T> records, long total, long current, long size) {
        return new PageResult<>(records, total, current, size);
    }
}
