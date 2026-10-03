package com.mengde.clouddrive;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@MapperScan("com.mengde.clouddrive.persistence")
@EnableFeignClients
public class FileServiceApplication {
    @org.springframework.context.annotation.Bean
    feign.Retryer feignRetryer() { return feign.Retryer.NEVER_RETRY; }
    public static void main(String[] args) {
        SpringApplication.run(FileServiceApplication.class, args);
    }
}
