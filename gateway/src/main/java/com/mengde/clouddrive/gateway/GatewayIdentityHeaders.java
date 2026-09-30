package com.mengde.clouddrive.gateway;

import java.util.List;

/**
 * 网关与下游之间的身份头契约，集中在一处定义。
 *
 * 名字取自 Day23《day23-service-boundaries.md》第 2.4 节写定的 X-User-Id / X-User-Role，
 * 额外加了 X-User-Name：单体的 AuthenticatedUser 有 username 字段，
 * 不透传就没法在下游还原出与本地验签完全等价的身份，"信任网关头"模式会缺一块。
 *
 * ALL 这个列表是**防伪造的唯一依据**：网关对列表里的每一个头，
 * 都先无条件删除客户端传来的值，再决定要不要由自己写入。
 * 新增身份头时只改这里一处，防伪造逻辑自动覆盖——分散定义迟早漏一个。
 */
public final class GatewayIdentityHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_NAME = "X-User-Name";
    public static final String USER_ROLE = "X-User-Role";

    public static final List<String> ALL = List.of(USER_ID, USER_NAME, USER_ROLE);

    private GatewayIdentityHeaders() { }
}
