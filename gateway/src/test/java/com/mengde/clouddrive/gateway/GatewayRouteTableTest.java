package com.mengde.clouddrive.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day24 路由表契约测试。
 *
 * <p>这组测试刻意**不依赖 Nacos**：默认形态下 `spring.cloud.nacos.discovery.enabled=false`、
 * `config.enabled=false`、`config.import-check.enabled=false`，所以在任何一台没有启动
 * Nacos 容器的机器上都能跑通。它验证的是"路由表这份配置被正确解析成了网关内部的
 * 路由对象"，而不是"转发真的通了"——后者需要真实后端进程，属于本日的手工验收步骤，
 * 不放进自动化测试里假装已经验证。
 */
@SpringBootTest(
        classes = CloudDriveGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "clouddrive.gateway.auth-uri=http://127.0.0.1:18080",
                "clouddrive.gateway.user-uri=http://127.0.0.1:18080",
                "clouddrive.gateway.file-uri=http://127.0.0.1:18080"
        })
class GatewayRouteTableTest {

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private GatewayProperties gatewayProperties;

    @Test
    void routeTableContainsExactlyTheSixDay24RoutesNamedAfterFutureServices() {
        Set<String> ids = routeLocator.getRoutes()
                .map(Route::getId)
                .collect(Collectors.toSet())
                .block();

        assertEquals(
                Set.of("auth-service-route", "user-service-route", "file-service-route",
                        "share-service-route", "api-docs-route", "monolith-health-route"),
                ids);
    }

    @Test
    void everyBusinessRouteStillPointsAtTheSameMonolithToday() {
        Map<String, String> uris = routeLocator.getRoutes()
                .collectMap(Route::getId, route -> route.getUri().toString())
                .block();

        assertAll(
                () -> assertEquals("http://127.0.0.1:18080", uris.get("auth-service-route")),
                () -> assertEquals("http://127.0.0.1:18080", uris.get("user-service-route")),
                () -> assertEquals("http://127.0.0.1:18080", uris.get("file-service-route")),
                () -> assertEquals("http://127.0.0.1:18080", uris.get("share-service-route")),
                // 今天四条业务路由指向同一个地址，是单体尚未拆分的真实状态；
                // 路由 id 已经按未来边界命名，Day25 只需要改 auth-service-route 一条。
                () -> assertEquals(uris.get("auth-service-route"), uris.get("file-service-route")));
    }

    @Test
    void defaultProfileResolvesStaticHttpUriInsteadOfLoadBalancedServiceName() {
        List<String> schemes = routeLocator.getRoutes()
                .map(route -> route.getUri().getScheme())
                .collectList()
                .block();

        assertAll(
                () -> assertTrue(schemes.stream().allMatch("http"::equals), "默认形态不应出现 lb 协议：" + schemes),
                () -> assertFalse(schemes.contains("lb"), "lb:// 只在 nacos profile 下生效"));
    }

    @Test
    void eachRouteDeclaresThePathPredicateItsFutureServiceOwns() {
        Map<String, List<String>> paths = gatewayProperties.getRoutes().stream()
                .collect(Collectors.toMap(
                        definition -> definition.getId(),
                        definition -> definition.getPredicates().stream()
                                .filter(predicate -> "Path".equals(predicate.getName()))
                                .flatMap(predicate -> predicate.getArgs().values().stream())
                                .collect(Collectors.toList())));

        assertAll(
                () -> assertEquals(List.of("/api/auth/**"), paths.get("auth-service-route")),
                () -> assertEquals(List.of("/api/entries/**"), paths.get("user-service-route")),
                () -> assertEquals(List.of("/api/files/**", "/api/upload-sessions/**"), paths.get("file-service-route")),
                () -> assertEquals(List.of("/api/shares/**"), paths.get("share-service-route")));
    }

    @Test
    void noBusinessPathIsClaimedByTwoDifferentRoutes() {
        List<String> allPaths = gatewayProperties.getRoutes().stream()
                .flatMap(definition -> definition.getPredicates().stream())
                .filter(predicate -> "Path".equals(predicate.getName()))
                .flatMap(predicate -> predicate.getArgs().values().stream())
                .collect(Collectors.toList());

        // 重复的 Path 意味着两条路由抢同一段路径，实际生效的是先匹配到的那条——
        // 这类冲突在配置文件里肉眼很难看出来，所以用断言钉死。
        assertEquals(allPaths.size(), Set.copyOf(allPaths).size(), "路由表中存在重复 Path：" + allPaths);
    }

    @Test
    void gatewayStampsEveryForwardedResponseSoManualVerificationCanTellItApart() {
        List<String> defaultFilters = gatewayProperties.getDefaultFilters().stream()
                .map(FilterDefinition::getName)
                .collect(Collectors.toList());

        assertTrue(defaultFilters.contains("AddResponseHeader"),
                "缺少统一响应头过滤器，手工验收时无法区分请求是直连后端还是经过网关：" + defaultFilters);
    }

    @Test
    void healthRouteStripsItsOwnPrefixBeforeForwarding() {
        List<FilterDefinition> filters = gatewayProperties.getRoutes().stream()
                .filter(definition -> "monolith-health-route".equals(definition.getId()))
                .flatMap(definition -> definition.getFilters().stream())
                .collect(Collectors.toList());

        // /backend-actuator/health 必须被削成 /actuator/health 才能命中后端端点，
        // 少了 StripPrefix=1 会 404，而且 404 来自后端、不来自网关，很容易误判。
        assertAll(
                () -> assertEquals(1, filters.size()),
                () -> assertEquals("StripPrefix", filters.get(0).getName()),
                () -> assertTrue(filters.get(0).getArgs().containsValue("1")));
    }

    @Test
    void predicateNamesAreResolvableSoTheContextWouldFailFastOnATypo() {
        List<String> predicateNames = gatewayProperties.getRoutes().stream()
                .flatMap(definition -> definition.getPredicates().stream())
                .map(PredicateDefinition::getName)
                .distinct()
                .collect(Collectors.toList());

        assertEquals(List.of("Path"), predicateNames);
    }
}
