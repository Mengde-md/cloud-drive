package com.base.auth;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 认证服务启动类
 *
 * 【面试知识点】
 * 1. @EnableFeignClients：启用 OpenFeign 声明式 HTTP 客户端
 *    - 启动时 Spring 会扫描 @FeignClient 注解的接口
 *    - 自动为这些接口生成代理实现类（动态代理）
 *    - 调用接口方法 = 发送 HTTP 请求到目标微服务
 *
 * 2. @MapperScan：MyBatis-Plus 的 Mapper 扫描
 *    - 自动扫描指定包下的 Mapper 接口，不需要每个 Mapper 都加 @Mapper
 */
@EnableFeignClients
@SpringBootApplication
@MapperScan("com.base.auth.mapper")
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
