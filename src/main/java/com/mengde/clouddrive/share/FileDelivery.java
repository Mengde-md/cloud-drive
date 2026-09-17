package com.mengde.clouddrive.share;
import java.io.InputStream;
public record FileDelivery(String displayName, String md5, long totalSize, long start, long end, InputStream input) { }
