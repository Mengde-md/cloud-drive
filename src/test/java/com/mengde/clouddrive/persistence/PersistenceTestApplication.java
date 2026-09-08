package com.mengde.clouddrive.persistence;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 持久化隔离测试的共享 Spring Boot 入口：仅扫描 persistence 包，
 * 不加载 web 控制器；两个测试类共用同一个入口以避免
 * 多个 @SpringBootConfiguration 冲突。
 */
@SpringBootApplication
@MapperScan("com.mengde.clouddrive.persistence")
public class PersistenceTestApplication {
}
