package com.base.gateway.config;

import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SaTokenConfigure {

    @Bean
    public SaReactorFilter getSaReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/**")
                .addExclude(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/shares/access/**",
                        // Swagger UI 相关路径放行（面试演示用）
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/webjars/**"
                )
                .setAuth(obj -> {
                    SaRouter.match("/**").check(r -> {
                        StpUtil.checkLogin();
                    });
                });
    }
}
