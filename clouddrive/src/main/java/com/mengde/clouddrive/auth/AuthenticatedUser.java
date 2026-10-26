package com.mengde.clouddrive.auth;

import java.util.List;

/**
 * 通过 JWT 校验后的调用者身份（Day15）。
 *
 * <p>它是整个应用的"身份唯一来源"：所有业务方法都从这个对象取 ownerId，
 * 而不是从请求参数里读 ownerId。只要有一个接口相信了请求体里的 ownerId，
 * 用户隔离就被打穿了。</p>
 */
public record AuthenticatedUser(long id, String username, List<String> roles) {

    public AuthenticatedUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
