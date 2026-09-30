package com.mengde.clouddrive.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Day25：单体是否信任网关注入的身份头。
 *
 * **默认 false，这是本类存在的全部意义。**
 *
 * 为什么默认关，而不是"拆分了就该信任网关"：
 *   1. 端口事实。单体的 8080 今天在本机直接可达，不经过网关。默认信任 X-User-Id
 *      等于任何能访问 8080 的人都可以自称任意用户——把"内网互信"当默认值，
 *      前提是先有内网边界，而今天还没有。
 *   2. 回归事实。95 项既有测试全部走本地验签路径。默认开会让这些测试跑在一条
 *      与生产不同的代码路径上，工程门就不再证明它原本证明的东西。
 *   3. 与 Day24 同一条纪律：新能力先以"默认关闭 + 契约测试钉死默认值"的形态进仓库，
 *      开启是部署决策，不是代码决策。
 *
 * 真实形态（compose 网络里只暴露网关端口）下由 profile 或环境变量置为 true。
 */
@ConfigurationProperties(prefix = "clouddrive.gateway.trusted-headers")
public class GatewayTrustProperties {

    private boolean enabled = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
