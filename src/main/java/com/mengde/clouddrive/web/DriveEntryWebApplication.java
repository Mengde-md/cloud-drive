package com.mengde.clouddrive.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

/**
 * Day06 启动类：把 Day01–Day05 已验证的领域对象、内存索引与文件快照
 * 以 Spring MVC HTTP 契约的形式暴露出来。
 *
 * <p>它不复用也不修改 Day01 的 {@code CloudDriveApplication}：那个类是
 * 工程基线的命令行入口，本类是 Web 入口，两者各自独立可运行。</p>
 */
@SpringBootApplication(scanBasePackages = "com.mengde.clouddrive")
@MapperScan({"com.mengde.clouddrive.persistence", "com.mengde.clouddrive.auth"})
public class DriveEntryWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriveEntryWebApplication.class, args);
    }
}
