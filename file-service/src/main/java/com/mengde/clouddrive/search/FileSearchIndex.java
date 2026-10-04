package com.mengde.clouddrive.search;

import java.util.List;

public interface FileSearchIndex {
    void replace(long owner, String generation, List<SearchDocument> documents);
    IndexPage search(long owner, String generation, SearchQuery query);
    void removeGeneration(long owner, String generation);
    record IndexPage(List<Long> ids, long total) { }
}
