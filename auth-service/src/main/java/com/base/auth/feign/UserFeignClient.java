package com.base.auth.feign;

import com.base.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 用户服务 Feign 客户端
 *
 * 【面试知识点】
 * 1. OpenFeign 是什么？
 *    - 声明式 HTTP 客户端，通过接口+注解定义远程调用，不用手写 RestTemplate
 *    - 底层自动集成 Ribbon/LoadBalancer 做负载均衡
 *    - 调用远程服务就像调用本地方法一样简单
 *
 * 2. @FeignClient 注解详解：
 *    - name = "user-service"：对应目标服务在 Nacos 中注册的服务名
 *      OpenFeign 会通过 Nacos 查到 user-service 的实际 IP 和端口，再发送 HTTP 请求
 *    - contextId = "authUserFeign"：当同一个服务有多个 FeignClient 时，用 contextId 区分
 *      避免 Spring 容器中出现 Bean 名称冲突
 *
 * 3. 方法签名规则：
 *    - @GetMapping 的路径要和目标服务的 Controller 保持一致
 *    - 参数用 @PathVariable 标注，名称要和路径变量对应
 *    - 返回类型建议用统一的 Result 包装类
 *
 * 4. 对比 Dubbo：
 *    - OpenFeign 走 HTTP 协议（简单直观，跨语言，调试方便）
 *    - Dubbo 走自定义协议（性能更高，适合内部微服务间高频调用）
 *    - 大厂通常用 Dubbo/gRPC，中小项目用 OpenFeign 足够
 *
 * 5. 使用示例：
 *    // 在 Service 层注入 Feign 客户端
 *    @Autowired
 *    private UserFeignClient userFeignClient;
 *
 *    // 调用远程服务（就像调用本地方法）
 *    Result<?> result = userFeignClient.getUserById(1L);
 */
@FeignClient(name = "user-service", contextId = "authUserFeign")
public interface UserFeignClient {

    /**
     * 通过 ID 查询用户信息
     *
     * 对应 user-service 的 GET /api/user/{id}
     *
     * 【调用流程】
     * 1. 代码调用 userFeignClient.getUserById(1L)
     * 2. OpenFeign 拦截方法调用
     * 3. 从 Nacos 查询 user-service 的实例列表（IP:端口）
     * 4. 通过 LoadBalancer 选择一个实例（负载均衡）
     * 5. 拼接完整 URL：http://user-service实例IP:端口/api/user/1
     * 6. 发送 HTTP GET 请求，获取响应
     * 7. 将 JSON 响应反序列化为 Result 对象返回
     *
     * @param id 用户 ID
     * @return 统一响应结果，包含用户信息
     */
    @GetMapping("/api/user/{id}")
    Result<?> getUserById(@PathVariable("id") Long id);
}
