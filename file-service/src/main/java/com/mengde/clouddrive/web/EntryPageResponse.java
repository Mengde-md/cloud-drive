package com.mengde.clouddrive.web;

import java.util.List;

/** Owner-scoped metadata page; page is zero-based. */
public record EntryPageResponse(List<EntryResponse> items, int page, int size, long total) { }
