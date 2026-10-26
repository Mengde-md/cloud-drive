package com.mengde.clouddrive.web;

import java.util.List;

/**
 * 条目列表分页响应（Day22）。
 */
public record EntryPageResponse(
        List<EntryResponse> items,
        int page,
        int size,
        long total) {
}
