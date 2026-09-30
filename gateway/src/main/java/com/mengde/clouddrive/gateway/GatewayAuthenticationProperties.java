package com.mengde.clouddrive.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 网关鉴权的可配置面。
 *
 * anonymous 列表**不是**"安全豁免清单随手加一条"的地方：每加一条都等于对公网开一个洞。
 * 它的默认值直接抄自单体 SecurityConfiguration 里的 permitAll 集合，
 * 保证"拆分前能匿名访问的路径"与"拆分后能匿名访问的路径"逐条相等——
 * 拆分最常见的事故不是漏放行（会立刻被发现），而是多放行（悄无声息）。
 */
@ConfigurationProperties(prefix = "clouddrive.gateway.authn")
public class GatewayAuthenticationProperties {

    /**
     * 关掉它网关就退化成纯转发。保留这个开关是为了让"网关鉴权到底有没有生效"
     * 可以被一次对照实验证明，而不是靠读配置猜。生产形态必须为 true。
     */
    private boolean enabled = true;

    /** 无需 token 即可通过网关的路径，Ant 风格。 */
    private List<String> anonymous = List.of(
            "/api/auth/register",
            "/api/auth/login",
            "/api/shares/*/download",
            "/actuator/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    );

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<String> getAnonymous() { return anonymous; }
    public void setAnonymous(List<String> anonymous) { this.anonymous = anonymous; }
}
