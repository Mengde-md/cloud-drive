package com.mengde.clouddrive.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 过滤链配置（Day15）。
 *
 * <p>逐项说明为什么这样配：</p>
 * <ul>
 *   <li><b>csrf 关闭</b>：CSRF 攻击依赖浏览器自动携带凭据（Cookie）。
 *       这里的凭据是需要 JS 显式放进 Authorization 头的 JWT，浏览器不会自动带，
 *       所以 CSRF token 在这个模型下没有保护作用，只会让所有调用方多一次握手。</li>
 *   <li><b>httpBasic / formLogin 关闭</b>：只保留一种认证入口。留着不用的入口
 *       等于多一条可能绕过 JWT 校验的路径。</li>
 *   <li><b>STATELESS</b>：不创建 HttpSession。否则 Spring 会为每个请求建会话，
 *       既浪费内存，也让"无状态"变成假的。</li>
 *   <li><b>默认拒绝</b>：anyRequest().authenticated()。
 *       白名单只列真正需要匿名访问的接口——新加接口时忘记配规则会变成 401（安全），
 *       而不是默认放行（危险）。</li>
 *   <li><b>addFilterBefore</b>：JWT 过滤器必须在用户名口令过滤器之前，
 *       否则请求到达授权判断时 SecurityContext 里还没有身份，永远是 401。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt 自带盐且可调工作因子，天然抵抗彩虹表；不要用 MD5/SHA 存口令。
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter,
                                                   RestAuthenticationFailureHandler failures) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 匿名可访问：注册、登录，以及凭提取码的分享下载
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/email-code",
                                "/api/auth/reset-password").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 分享的查看与下载都是匿名的：提取码本身就是凭证。
                        // 注意只放行这两个具体动作，/api/shares/*/save（转存）不在其中——
                        // 转存要写进某个人的网盘，必须先知道是谁，所以落到下面的 authenticated()。
                        .requestMatchers("/api/shares/*/info", "/api/shares/*/download").permitAll()
                        // 运维探活
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // 其余一律需要登录
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(failures)
                        .accessDeniedHandler(failures))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
