package com.mengde.clouddrive.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Day24 边缘网关进程。
 *
 * <p>本进程是 CloudDrive 第一个独立于单体之外的部署单元，但它**不承担任何业务逻辑**：
 * Day24 的唯一职责是把外部请求按 Day23 定下的四服务边界（auth / user / file / gateway）
 * 转发到后端，并把"后端地址从哪里来"这件事从硬编码变成可发现、可配置。
 *
 * <p>今天后端仍然只有一个单体进程，四条业务路由的 uri 全部指向同一个地址；
 * Day25 拆出 auth-service 时，只需要把 {@code auth-service-route} 那一条的 uri
 * 换成新的服务名，其余路由不动——这正是把路由表按未来边界而不是按今天进程数
 * 划分的原因。
 */
@SpringBootApplication
public class CloudDriveGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudDriveGatewayApplication.class, args);
    }
}
