package com.mengde.clouddrive.web;

import java.time.LocalDateTime;

public record TrashEntryResponse(long id, Long parentId, String displayName, String kind, Long byteSize,
                                 LocalDateTime deletedAt) { }
