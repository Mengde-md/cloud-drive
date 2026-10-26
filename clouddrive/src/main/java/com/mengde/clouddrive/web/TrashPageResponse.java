package com.mengde.clouddrive.web;

import java.util.List;

/**
 * 回收站分页响应（Day22）。
 * 除数据本身外必须带上 page/size/total，否则前端无法知道还有没有下一页。
 */
public record TrashPageResponse(
        List<TrashEntryResponse> items,
        int page,
        int size,
        long total) {
}
