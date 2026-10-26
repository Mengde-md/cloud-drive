package com.mengde.clouddrive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day01 冒烟测试：证明工程能编译、能运行、能被断言。
 */
class CloudDriveApplicationSmokeTest {

    @Test
    void exposesStableBootstrapMetadata() {
        String banner = CloudDriveApplication.startupBanner();

        assertAll(
                () -> assertEquals("cloud-drive-learning", CloudDriveApplication.PROJECT_NAME),
                () -> assertEquals(17, CloudDriveApplication.REQUIRED_JAVA_FEATURE),
                () -> assertNotNull(banner),
                () -> assertFalse(banner.isBlank()),
                () -> assertTrue(banner.contains(CloudDriveApplication.PROJECT_NAME)),
                () -> assertTrue(banner.contains("Java 17")));
    }

    @Test
    void runsOnTheConfiguredJavaFeature() {
        assertEquals(CloudDriveApplication.REQUIRED_JAVA_FEATURE, Runtime.version().feature());
    }
}
