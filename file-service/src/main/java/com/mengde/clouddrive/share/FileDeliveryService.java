package com.mengde.clouddrive.share;

import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.FileShareMapper;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import com.mengde.clouddrive.storage.ObjectStorage;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class FileDeliveryService {
    private final DriveEntryRepository entries; private final FileShareMapper shares; private final ObjectStorage objects; private final PasswordEncoder passwords;
    public FileDeliveryService(DriveEntryRepository entries, FileShareMapper shares, ObjectStorage objects, PasswordEncoder passwords) { this.entries=entries; this.shares=shares; this.objects=objects; this.passwords=passwords; }
    public FileShareRow create(long ownerId, long documentId, String passcode, LocalDateTime expiresAt) {
        StoredDocumentRow document = entries.findStoredDocumentByOwnerAndId(ownerId, documentId);
        FileShareRow row = new FileShareRow(); row.setToken(UUID.randomUUID().toString()); row.setDocumentId(document.getId()); row.setOwnerId(ownerId); row.setPasscodeHash(passwords.encode(passcode)); row.setExpiresAt(expiresAt); shares.insert(row); return row;
    }
    public FileDelivery ownerDownload(long ownerId, long documentId, String range) throws IOException { return delivery(entries.findStoredDocumentByOwnerAndId(ownerId, documentId), range); }
    public FileDelivery sharedDownload(String token, String passcode, String range) throws IOException {
        FileShareRow share = shares.findByToken(token).orElseThrow(ShareAccessException::new);
        if ((share.getExpiresAt()!=null && !share.getExpiresAt().isAfter(LocalDateTime.now())) || !passwords.matches(passcode, share.getPasscodeHash())) throw new ShareAccessException();
        return delivery(entries.findStoredDocumentByOwnerAndId(share.getOwnerId(), share.getDocumentId()), range);
    }
    private FileDelivery delivery(StoredDocumentRow row, String header) throws IOException {
        long[] range = parse(header, row.getByteSize()); long length = range[1]-range[0]+1;
        return new FileDelivery(row.getDisplayName(), row.getContentMd5(), row.getByteSize(), range[0], range[1], objects.open(row.getStorageKey(), range[0], length));
    }
    private long[] parse(String header, long size) {
        if (header==null || header.isBlank()) return new long[]{0,size-1};
        if (!header.startsWith("bytes=") || header.contains(",")) throw new IllegalArgumentException("only one bytes range is supported");
        String[] parts=header.substring(6).split("-",-1); if(parts.length!=2) throw new IllegalArgumentException("invalid Range header");
        try { long start=parts[0].isBlank()? -1:Long.parseLong(parts[0]); long end=parts[1].isBlank()? -1:Long.parseLong(parts[1]);
            if(start<0) { if(end<=0) throw new IllegalArgumentException("invalid suffix range"); start=Math.max(0,size-end); end=size-1; }
            else if(end<0) end=size-1;
            if(start>=size || end<start) throw new IllegalArgumentException("range outside file"); return new long[]{start,Math.min(end,size-1)};
        } catch(NumberFormatException ex){ throw new IllegalArgumentException("invalid Range header"); }
    }
}
