package com.mengde.clouddrive.application;

import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.web.EntryCreateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Day12 的应用服务边界：HTTP 层只表达请求/响应，Repository 只表达数据访问，
 * 本类负责把一次业务用例放进同一个 Spring 事务代理边界。
 */
@Service
public class DriveEntryApplicationService {

    private final DriveEntryRepository repository;

    public DriveEntryApplicationService(DriveEntryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<DriveEntry> listAll() {
        return repository.findAll();
    }

    @Transactional
    public DriveEntry create(EntryCreateRequest request) {
        DriveEntry entry = request.toEntry();
        verifyParentExists(entry.parentId());
        return repository.save(entry);
    }

    @Transactional(readOnly = true)
    public DriveEntry findRequired(long id) {
        return repository.findById(id);
    }

    @Transactional
    public void rename(long id, String displayName) {
        repository.rename(id, displayName);
    }

    private void verifyParentExists(Long parentId) {
        if (parentId != null) {
            repository.findById(parentId);
        }
    }
}
