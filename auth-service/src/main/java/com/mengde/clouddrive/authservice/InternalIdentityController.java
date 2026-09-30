package com.mengde.clouddrive.authservice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Day23 第 2.1 节的"对内契约"：GET /internal/users/{id}/identity。
 *
 * 为什么今天就把它建出来，而不是等 Day26 有人调用时再建：这个路径的**存在本身**
 * 决定了网关路由表要不要放行它。答案是不放行——/internal/** 不在任何一条网关路由的
 * Path 谓词里，也就是说它从公网入口**不可达**，只能由集群内的其它服务直连。
 * 这条"内外分离"是今天就能用测试钉死的，Day26 接 Feign 时不用再改网关。
 *
 * 今天没有任何调用方，这一点在 Final 里如实写明，不声称它已经被使用。
 */
@RestController
public class InternalIdentityController {

    private final AuthService service;

    public InternalIdentityController(AuthService service) { this.service = service; }

    @GetMapping("/internal/users/{id}/identity")
    public Identity identity(@PathVariable("id") long id) {
        UserAccountRow row = service.identity(id);
        return new Identity(row.getId(), row.getUsername(), row.getRole());
    }

    public record Identity(long id, String username, String role) { }
}
