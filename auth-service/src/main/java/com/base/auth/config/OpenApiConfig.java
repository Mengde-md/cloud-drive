package com.base.auth.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 文档配置
 * <p>
 * 访问地址：http://localhost:8090/swagger-ui.html
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI authOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CloudDrive 认证服务 API")
                        .description("用户注册、登录、Token 管理")
                        .version("1.0.0")
                        .contact(new Contact().name("CloudDrive")));
    }
}
