package com.mengde.clouddrive.storage;

import java.io.IOException;
import java.io.InputStream;

public interface ObjectStorage {
    StoredObject store(InputStream input, String originalFilename) throws IOException;
    InputStream open(String storageKey, long start, long length) throws IOException;
    void delete(String storageKey) throws IOException;
}
