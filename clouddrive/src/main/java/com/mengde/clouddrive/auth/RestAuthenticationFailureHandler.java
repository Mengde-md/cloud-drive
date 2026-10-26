package com.mengde.clouddrive.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengde.clouddrive.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 鉴权失败的统一出口（Day15）：401 与 403 都用和业务错误相同的 ApiError 结构。
 *
 * <p>为什么要自己实现：Spring Security 默认的 401 返回空体加 WWW-Authenticate 头，
 * 调用方拿到的响应结构和业务错误完全不同，前端得写两套解析逻辑。
 * 统一成一种错误体后，调用方只需要看 status 字段。</p>
 *
 * <p>同时注意：消息是固定文案，不回显异常堆栈，也不说明"用户不存在"还是"令牌过期"，
 * 避免给攻击者提供可用于枚举的差异信息。</p>
 */
@Component
public class RestAuthenticationFailureHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    public RestAuthenticationFailureHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "authentication is required or the token is invalid");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "the current identity is not allowed to access this resource");
    }

    private void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(),
                new ApiError(status.value(), status.getReasonPhrase(), message));
    }
}
