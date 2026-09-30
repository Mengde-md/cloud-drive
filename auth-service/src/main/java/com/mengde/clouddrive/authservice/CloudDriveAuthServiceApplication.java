package com.mengde.clouddrive.authservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Day25：CloudDrive 的第一个真正独立的部署单元。
 *
 * 它与单体进程共用同一个 MySQL 实例和同一张 user_account 表——这是本日**刻意保留**的现状，
 * 不是疏忽。Day23《day23-service-boundaries.md》第 2.1 节把 user_account 的身份子集
 * （id/username/password_hash/role/created_at）判给 auth-service、把 quota_bytes 判给
 * user-service，但物理分库与 user_profile 表的迁出属于 user-service 的工作，本日不做。
 * 今天能验证的是"身份认证可以作为独立进程被网关路由到"，不是"数据库已经拆开"。
 */
@SpringBootApplication
public class CloudDriveAuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CloudDriveAuthServiceApplication.class, args);
    }
}
