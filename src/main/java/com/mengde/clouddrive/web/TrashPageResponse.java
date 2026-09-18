package com.mengde.clouddrive.web;

import java.util.List;

/** Paginated recycle-bin view; unlike the active list it exposes deletion time. */
public record TrashPageResponse(List<TrashEntryResponse> items, int page, int size, long total) { }
