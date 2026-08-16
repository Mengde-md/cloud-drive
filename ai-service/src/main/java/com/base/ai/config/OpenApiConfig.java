package com.base.ai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 文档配置
 * <p>
 * 访问地址：http://localhost:8093/swagger-ui.html
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aiOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CloudDrive AI 文档智能 API")
                        .description("文档解析（Tika）、文本分块、RAG 问答")
                        .version("1.0.0")
                        .contact(new Contact().name("CloudDrive")));
    }
}
