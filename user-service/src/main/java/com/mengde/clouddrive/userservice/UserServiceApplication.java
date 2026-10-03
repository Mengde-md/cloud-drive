package com.mengde.clouddrive.userservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@MapperScan(value = "com.mengde.clouddrive.userservice", annotationClass = org.apache.ibatis.annotations.Mapper.class)
@EnableFeignClients
public class UserServiceApplication {
    @org.springframework.context.annotation.Bean
    feign.Retryer feignRetryer() { return feign.Retryer.NEVER_RETRY; }
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
