package com.base.user.controller;

import com.base.common.Result;
import com.base.user.entity.User;
import com.base.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable Long id) {
        User user = userService.getById(id);
        if (user == null) return Result.error(404, "用户不存在");
        return Result.success(user);
    }

    @PutMapping("/{id}/space")
    public Result<Void> updateSpace(@PathVariable Long id,
                                    @RequestParam Long useSpace,
                                    @RequestParam Long totalSpace) {
        userService.updateSpace(id, useSpace, totalSpace);
        return Result.success(null);
    }
}
