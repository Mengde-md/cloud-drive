package com.mengde.clouddrive.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AiServiceApplication {
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--mcp-stdio")) {
            CloudDriveMcpServer.main(new String[0]);
            return;
        }
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
