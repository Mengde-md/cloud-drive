package com.mengde.clouddrive.authservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * auth-service 的对外 HTTP 契约。
 *
 * 为什么用 @WebMvcTest + mock 掉 AuthService，而不是 @SpringBootTest 连真库：
 * 这一层要验的是"路径、状态码、响应字段名、安全规则"，这些与数据库无关。
 * 让 auth-service 的工程门不依赖 MySQL，意味着它在任何一台机器上都能跑；
 * 真正跨进程的端到端验证是本日手工验收的第 4 项，用真实容器做，两者不互相替代。
 */
@WebMvcTest(controllers = {AuthController.class, InternalIdentityController.class})
@Import({SecurityConfiguration.class, JwtTokenService.class})
class AuthServiceHttpContractTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenService tokens;

    @MockBean AuthService service;

    private static UserAccountRow row(long id, String username, String role) {
        UserAccountRow r = new UserAccountRow();
        r.setId(id); r.setUsername(username); r.setRole(role); r.setPasswordHash("irrelevant");
        return r;
    }

    private String body(String username, String password) throws Exception {
        return json.writeValueAsString(new AuthController.Credentials(username, password));
    }

    @Test
    @DisplayName("注册成功返回 201 与 {id, username, role}，且响应体里不出现 password 或 hash")
    void registerReturns201() throws Exception {
        given(service.register(anyString(), anyString())).willReturn(row(7L, "alice", "USER"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("alice", "correct-horse")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("用户名重复返回 409 而不是 500——靠唯一索引判重，不靠先查后插")
    void duplicateUsernameReturns409() throws Exception {
        willThrow(new UsernameAlreadyExistsException()).given(service).register(anyString(), anyString());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("alice", "correct-horse")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("登录成功返回 {accessToken, expiresAt}——字段名与单体逐字相同，网关改指向时前端零改动")
    void loginReturnsMonolithCompatibleFieldNames() throws Exception {
        Instant exp = Instant.now().plus(30, ChronoUnit.MINUTES);
        given(service.login(anyString(), anyString())).willReturn(new JwtTokenService.IssuedToken("a.b.c", exp));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("alice", "correct-horse")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a.b.c"))
                .andExpect(jsonPath("$.expiresAt").exists())
                // Day23 文档写的是 {token, expiresIn}。这里钉死的是真实代码的字段名，
                // 若将来有人"按文档修正"成 token/expiresIn，这两条断言会立刻失败。
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.expiresIn").doesNotExist());
    }

    @Test
    @DisplayName("用户不存在与密码错误必须返回同一个 401——区分开就是一个用户名枚举接口")
    void wrongCredentialsReturn401() throws Exception {
        willThrow(new InvalidCredentialsException()).given(service).login(anyString(), anyString());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("ghost", "correct-horse")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid credentials"));
    }

    @Test
    @DisplayName("请求体校验失败返回 400，且错误信息里不回显收到的密码")
    void invalidBodyReturns400WithoutEchoingPassword() throws Exception {
        String result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("al", "short")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(result).doesNotContain("short");
    }

    @Test
    @DisplayName("/api/auth/me 不带 token 返回 401，带合法 token 返回 token 里的身份")
    void meRequiresValidToken() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

        String token = tokens.issue(row(42L, "bob", "USER")).value();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }

    @Test
    @DisplayName("Authorization 头存在但格式不对，必须 401 而不是当成匿名放行")
    void malformedAuthorizationHeaderIs401() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Token abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/internal/** 需要服务间令牌，不能被匿名请求直接访问")
    void internalIdentityRequiresServiceToken() throws Exception {
        given(service.identity(anyLong())).willReturn(row(9L, "carol", "USER"));
        mvc.perform(get("/internal/users/9/identity"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/users/9/identity")
                        .header(InternalServiceTokenFilter.HEADER_NAME,
                                "day25-test-only-internal-token-at-least-32-bytes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("carol"));
    }
}
