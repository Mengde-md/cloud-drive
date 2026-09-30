package com.mengde.clouddrive.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Day25：单体侧"信任网关身份头"过滤器的单元契约。
 *
 * <p>不起 Spring 上下文、不连数据库：这一层验的是过滤器自身的判断逻辑，
 * 每一条都对应一种具体的越权或回归风险，跑起来是毫秒级的。
 */
class TrustedGatewayHeaderFilterTest {

    private final GatewayTrustProperties properties = new GatewayTrustProperties();
    private final TrustedGatewayHeaderAuthenticationFilter filter =
            new TrustedGatewayHeaderAuthenticationFilter(properties);

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    private void run(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
    }

    private static MockHttpServletRequest withHeaders(String id, String name, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/entries");
        if (id != null) request.addHeader(TrustedGatewayHeaderAuthenticationFilter.USER_ID, id);
        if (name != null) request.addHeader(TrustedGatewayHeaderAuthenticationFilter.USER_NAME, name);
        if (role != null) request.addHeader(TrustedGatewayHeaderAuthenticationFilter.USER_ROLE, role);
        return request;
    }

    @Test
    @DisplayName("默认（开关关闭）时，带身份头也不建立认证——95 项既有回归据此不受影响")
    void disabledByDefaultIgnoresHeadersEntirely() throws Exception {
        assertThat(properties.isEnabled()).as("这个默认值是本日的安全前提").isFalse();
        run(withHeaders("1", "root", "ADMIN"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("开启后，完整身份头还原出与本地验签等价的 AuthenticatedUser")
    void enabledBuildsAuthenticationFromHeaders() throws Exception {
        properties.setEnabled(true);
        run(withHeaders("42", "alice", "USER"));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(new AuthenticatedUser(42L, "alice", List.of("USER")));
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("已经由本地验签认证过的请求不会被头覆盖——自己验出来的身份更可信")
    void doesNotOverrideAnExistingAuthentication() throws Exception {
        properties.setEnabled(true);
        AuthenticatedUser real = new AuthenticatedUser(7L, "bob", List.of("USER"));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(real, null, List.of()));

        run(withHeaders("1", "root", "ADMIN"));

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(real);
    }

    @Test
    @DisplayName("X-User-Id 不是数字或缺失时当匿名处理，绝不用默认值兜底")
    void refusesToGuessWhenTheIdHeaderIsUnusable() throws Exception {
        properties.setEnabled(true);

        run(withHeaders("not-a-number", "alice", "USER"));
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("解析失败兜底成 userId=0 是越权的经典入口").isNull();

        run(withHeaders(null, "alice", "USER"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        run(withHeaders("   ", "alice", "USER"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("多角色以逗号分隔，空角色退化为空列表而不是一个空字符串角色")
    void parsesRolesDefensively() throws Exception {
        properties.setEnabled(true);

        run(withHeaders("5", "alice", "USER, ADMIN ,"));
        AuthenticatedUser user = (AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(user.roles()).containsExactly("USER", "ADMIN");

        SecurityContextHolder.clearContext();
        run(withHeaders("6", "alice", ""));
        AuthenticatedUser noRole = (AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(noRole.roles()).isEmpty();
    }
}
