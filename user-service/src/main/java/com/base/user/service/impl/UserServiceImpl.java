package com.base.user.service.impl;

import com.base.user.entity.User;
import com.base.user.mapper.UserMapper;
import com.base.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    @Override
    public User getById(Long id) {
        return userMapper.selectById(id);
    }

    @Override
    public void updateSpace(Long id, Long useSpace, Long totalSpace) {
        User user = userMapper.selectById(id);
        if (user != null) {
            user.setUseSpace(useSpace);
            user.setTotalSpace(totalSpace);
            userMapper.updateById(user);
        }
    }
}
