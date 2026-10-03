package com.mengde.clouddrive.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "cloud-drive-user")
interface UserQuotaClient {
    @PostMapping("/internal/users/{id}/quota/reserve")
    String reserve(@PathVariable("id") long id, @RequestBody ReserveRequest request,
                   @RequestHeader("X-CloudDrive-Internal-Token") String token);

    @PostMapping("/internal/users/{id}/quota/{reservationId}/commit")
    void commit(@PathVariable("id") long id, @PathVariable("reservationId") String reservationId,
                @RequestHeader("X-CloudDrive-Internal-Token") String token);

    @PostMapping("/internal/users/{id}/quota/{reservationId}/release")
    void release(@PathVariable("id") long id, @PathVariable("reservationId") String reservationId,
                 @RequestHeader("X-CloudDrive-Internal-Token") String token);

    record ReserveRequest(String reservationId, long amount, long usedBytes) { }
}
