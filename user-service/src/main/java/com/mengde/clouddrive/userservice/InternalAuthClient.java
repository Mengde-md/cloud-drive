package com.mengde.clouddrive.userservice;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "cloud-drive-auth")
interface InternalAuthClient {
    @GetMapping("/internal/users/{id}/identity")
    Identity identity(@PathVariable("id") long id,
                      @RequestHeader("X-CloudDrive-Internal-Token") String serviceToken);

    record Identity(long id, String username, String role) { }
}
