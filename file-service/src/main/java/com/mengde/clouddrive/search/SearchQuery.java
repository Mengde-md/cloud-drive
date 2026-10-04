package com.mengde.clouddrive.search;

public record SearchQuery(String q, int page, int size) {
    public SearchQuery {
        if (q == null || q.isBlank() || q.trim().length() > 100) {
            throw new IllegalArgumentException("q must contain 1 to 100 characters");
        }
        q = q.trim();
        if (page < 0 || size < 1 || size > 50 || ((long) page + 1) * size > 10000) {
            throw new IllegalArgumentException("page must be nonnegative, size 1 to 50, window at most 10000");
        }
    }
    public int offset() { return page * size; }
}
