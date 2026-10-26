package com.mengde.clouddrive.web;

import com.mengde.clouddrive.auth.JwtProperties;
import com.mengde.clouddrive.schedule.TrashProperties;
import com.mengde.clouddrive.storage.StorageProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Web 入口（Day06 建立，之后逐日扩展为完整的云盘后端）。
 *
 * <p>{@code @SpringBootApplication} 的组件扫描以本类所在包为起点，
 * 所以扫描根是 {@code com.mengde.clouddrive.web}——这会漏掉 auth/application/persistence 等包。
 * 因此这里显式把 scanBasePackages 提到父包 {@code com.mengde.clouddrive}。
 * 忘了这一步的典型现象是启动报 "required a bean of type ... that could not be found"。</p>
 */
@SpringBootApplication(
        scanBasePackages = "com.mengde.clouddrive",
        // 排除默认用户自动配置：本系统的身份来自 JWT，没有表单登录也没有 Basic 认证，
        // 留着它只会在每次启动时生成一个用不到的随机口令，反而让人以为系统有个默认账号。
        exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan({"com.mengde.clouddrive.persistence", "com.mengde.clouddrive.auth"})
@EnableConfigurationProperties({JwtProperties.class, StorageProperties.class, TrashProperties.class})
// 开启定时任务：回收站过期清理靠它跑。默认线程池只有 1 个线程，
// 当前只有一个任务够用；将来任务变多要显式配置线程数，否则会互相阻塞。
@EnableScheduling
public class DriveEntryWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriveEntryWebApplication.class, args);
    }
}
