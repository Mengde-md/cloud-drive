package com.mengde.clouddrive.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day24 配置契约测试：默认形态必须把 Nacos 全部关掉，而且 classpath 上**每一份**
 * application.properties 都要关掉。
 *
 * <p>为什么要遍历所有副本，而不是只读一份。本仓库同时存在
 * {@code src/main/resources/application.properties} 与
 * {@code src/test/resources/application.properties}（后者是 Day15 为测试签名密钥加的）。
 * 两者**同名**，在测试 classpath 上 test-classes 排在 classes 前面，所以
 * {@code classpath:/application.properties} 只会命中测试那一份，主配置里的内容在测试中
 * 完全不生效——而且没有任何警告。
 *
 * <p>Day24 第一次构建就栽在这里：Nacos 的三行禁用开关只写进了主配置，测试读不到，
 * spring-cloud-commons 的 {@code ConfigDataMissingEnvironmentPostProcessor} 按默认值
 * 执行检查并抛出 {@code No spring.config.import set}，43 个 Spring 上下文测试在启动阶段
 * 全部失败，报错信息里没有一个字提到 Nacos。遍历断言就是为了让"某一份副本漏了这几行"
 * 这件事立刻以一条含文件 URL 的明确失败暴露出来。
 *
 * <p>本测试刻意不启动 Spring 上下文：它读 classpath 上的配置文件本身，
 * 因此不需要 MySQL / Redis / Nacos，也不受 {@code @SpringBootTest(properties = ...)} 覆盖的影响。
 */
class NacosDefaultsOffContractTest {

    /** classpath 上所有同名 application.properties，连同它们的来源 URL。 */
    private List<Copy> allApplicationPropertiesCopies() throws IOException {
        List<Copy> copies = new ArrayList<>();
        Enumeration<URL> urls = getClass().getClassLoader().getResources("application.properties");
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            Properties properties = new Properties();
            try (InputStream stream = url.openStream()) {
                properties.load(stream);
            }
            copies.add(new Copy(url, properties));
        }
        return copies;
    }

    private Properties nacosProfile() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("application-nacos.properties")) {
            assertNotNull(stream, "缺少 application-nacos.properties，真实注册发现无从启用");
            properties.load(stream);
        }
        return properties;
    }

    @Test
    void everyApplicationPropertiesCopyKeepsNacosDiscoveryAndConfigDisabled() throws IOException {
        List<Copy> copies = allApplicationPropertiesCopies();
        assertFalse(copies.isEmpty(), "classpath 上找不到任何 application.properties");

        assertAll(copies.stream().map(copy -> (org.junit.jupiter.api.function.Executable) () -> assertAll(
                "副本 " + copy.url(),
                () -> assertEquals("false", copy.properties().getProperty("spring.cloud.nacos.discovery.enabled"),
                        "该副本未关闭 Nacos 服务发现：" + copy.url()),
                () -> assertEquals("false", copy.properties().getProperty("spring.cloud.nacos.config.enabled"),
                        "该副本未关闭 Nacos 配置中心：" + copy.url()),
                // 少了这一行，spring-cloud-commons 会强制要求 spring.config.import，
                // 默认形态下没有这一项，于是每个 @SpringBootTest 都在启动期直接失败。
                () -> assertEquals("false", copy.properties().getProperty("spring.cloud.nacos.config.import-check.enabled"),
                        "该副本未关闭 import-check：" + copy.url()))));
    }

    @Test
    void theCopyThatActuallyWinsOnTheTestClasspathIsTheFirstOne() throws IOException {
        // 记录这个事实本身：第一份就是测试实际生效的那一份。
        // 断言它同时带着 Nacos 开关与服务名，等于断言"测试真正读到的配置是完整的"。
        Copy effective = allApplicationPropertiesCopies().get(0);

        assertAll(
                () -> assertEquals("false", effective.properties().getProperty("spring.cloud.nacos.config.enabled")),
                () -> assertNotNull(effective.properties().getProperty("spring.application.name"),
                        "测试实际生效的那份配置缺少 spring.application.name：" + effective.url()));
    }

    @Test
    void serviceNameMatchesWhatTheGatewayResolves() throws IOException {
        List<String> names = allApplicationPropertiesCopies().stream()
                .map(copy -> copy.properties().getProperty("spring.application.name"))
                .filter(java.util.Objects::nonNull)
                .toList();

        assertFalse(names.isEmpty(), "没有任何副本声明 spring.application.name，nacos 形态下网关 lb:// 无目标可解析");
        assertTrue(names.stream().allMatch(name -> name.contains("cloud-drive-app")),
                "服务名必须与 gateway/application-nacos.yml 中 CLOUDDRIVE_APP_NAME 的默认值一致：" + names);
    }

    @Test
    void nacosProfileExistsAndTurnsDiscoveryBackOn() throws IOException {
        Properties properties = nacosProfile();

        assertAll(
                () -> assertEquals("true", properties.getProperty("spring.cloud.nacos.discovery.enabled")),
                () -> assertEquals("true", properties.getProperty("spring.cloud.nacos.config.enabled")),
                // optional: 前缀保证 Nacos 上还没有这个 dataId 时应用照常启动。
                () -> assertTrue(properties.getProperty("spring.config.import").startsWith("optional:nacos:"),
                        "spring.config.import 必须带 optional: 前缀：" + properties.getProperty("spring.config.import")));
    }

    @Test
    void credentialsAreNeverHardcodedInTheNacosProfile() throws IOException {
        Properties properties = nacosProfile();

        // 与 Day23 冻结环境时同一条红线：配置文件里只允许出现 ${ENV_VAR:default} 占位符，
        // 不允许出现任何真实的地址口令组合。
        assertAll(
                () -> assertTrue(properties.getProperty("spring.cloud.nacos.config.username").startsWith("${")),
                () -> assertTrue(properties.getProperty("spring.cloud.nacos.config.password").startsWith("${")),
                () -> assertTrue(properties.getProperty("spring.cloud.nacos.discovery.server-addr").startsWith("${")));
    }

    private record Copy(URL url, Properties properties) { }
}
