package com.mengde.clouddrive.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day25：把"信任网关身份头默认关闭"这个契约钉死在配置文件层面。
 *
 * <p>做法沿用 Day24 的 NacosDefaultsOffContractTest：**遍历 classpath 上每一份同名
 * application.properties**，而不是只读第一份。理由写在 docs/day24/Final.md——
 * src/test/resources 与 src/main/resources 下的同名文件会互相遮蔽且没有任何警告，
 * 只读第一份的断言在"某一份漏写了"时给出的失败信息完全无法定位。
 */
class TrustedGatewayHeadersDefaultOffContractTest {

    private static final String KEY = "clouddrive.gateway.trusted-headers.enabled";

    @Test
    @DisplayName("classpath 上每一份 application.properties 都必须把信任开关写成 false")
    void everyCopyKeepsTrustedHeadersDisabled() throws IOException {
        Resource[] copies = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/application.properties");

        assertThat(copies).as("至少应存在主配置与测试配置两份").hasSizeGreaterThanOrEqualTo(1);

        for (Resource copy : copies) {
            Properties properties = new Properties();
            try (InputStream in = copy.getInputStream()) { properties.load(in); }
            String raw = properties.getProperty(KEY);

            assertThat(raw).as("这份副本缺少 %s：%s", KEY, copy.getURL()).isNotNull();
            // 主配置写的是 ${CLOUDDRIVE_TRUST_GATEWAY_HEADERS:false}，测试配置写的是裸 false。
            // 两种写法解析后的默认值都必须是 false。
            assertThat(raw.replace("${CLOUDDRIVE_TRUST_GATEWAY_HEADERS:", "").replace("}", "").trim())
                    .as("这份副本把信任开关默认打开了：%s", copy.getURL())
                    .isEqualTo("false");
        }
    }

    @Test
    @DisplayName("主配置必须允许用环境变量在部署期覆盖，而不是把 false 写死")
    void productionCopyStaysOverridableByEnvironment() throws IOException {
        Resource[] copies = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/application.properties");

        boolean anyOverridable = false;
        for (Resource copy : copies) {
            Properties properties = new Properties();
            try (InputStream in = copy.getInputStream()) { properties.load(in); }
            String raw = properties.getProperty(KEY, "");
            if (raw.contains("${CLOUDDRIVE_TRUST_GATEWAY_HEADERS:")) { anyOverridable = true; }
        }
        // 写死 false 会让真实形态（只暴露网关端口）无法启用，进而逼着后人去改代码。
        assertThat(anyOverridable)
                .as("没有任何一份配置支持 CLOUDDRIVE_TRUST_GATEWAY_HEADERS 覆盖").isTrue();
    }
}
