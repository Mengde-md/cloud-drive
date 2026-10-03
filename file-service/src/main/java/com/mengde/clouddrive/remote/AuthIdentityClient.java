package com.mengde.clouddrive.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "cloud-drive-auth")
interface AuthIdentityClient {
    @GetMapping("/internal/users/{id}/identity")
    Identity identity(@PathVariable("id") long id,
                      @RequestHeader("X-CloudDrive-Internal-Token") String token);

    record Identity(long id, String username, String role) { }
}
