package com.mengde.clouddrive.ai;

import java.util.List;

final class RagEvaluationData {
    record Source(String name, String text) { }
    record Case(String id, String question, String expectedSource) { }
    static final List<Source> SOURCES = List.of(
            new Source("trash.txt", RagIndexTest.TRASH),
            new Source("upload.txt", "Large files are uploaded in numbered chunks. The server verifies the MD5 checksum "
                    + "before merging all chunks into the final file. A checksum mismatch rejects the upload."),
            new Source("sharing.md", "A file sharing link can be protected by a password and an expiration date. "
                    + "After the expiration date, the sharing link is no longer accessible."),
            new Source("quota.txt", "Each user has a storage quota. Uploading a file reserves its byte size. "
                    + "When storage usage exceeds the quota, the server refuses the upload."));
    // Frozen small engineering benchmark, not a held-out production-quality evaluation.
    static final List<Case> CASES = List.of(
            new Case("positive-trash-1", "How long are deleted files kept in the recycle bin?", "trash.txt"),
            new Case("positive-trash-2", "Can users restore deleted files during the retention period?", "trash.txt"),
            new Case("positive-upload-1", "How does the server verify uploaded file chunks before merging?", "upload.txt"),
            new Case("positive-upload-2", "What happens when an upload checksum does not match?", "upload.txt"),
            new Case("positive-share", "Can a file sharing link have a password and expiration date?", "sharing.md"),
            new Case("positive-quota", "What happens if a file upload exceeds the user's storage quota?", "quota.txt"),
            new Case("negative-astronomy", "What is the mass of the planet Jupiter?", null),
            new Case("negative-food", "How do I bake a chocolate cake?", null),
            new Case("negative-travel", "What is the capital city of Argentina?", null),
            new Case("negative-music", "Who composed the opera The Magic Flute?", null));
    private RagEvaluationData() { }
}
