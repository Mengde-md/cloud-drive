package com.base.user.service;

import com.base.user.entity.User;

public interface UserService {
    User getById(Long id);
    void updateSpace(Long id, Long useSpace, Long totalSpace);
}
