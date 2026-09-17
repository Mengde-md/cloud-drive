package com.mengde.clouddrive.web;

import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.share.FileDelivery;
import com.mengde.clouddrive.share.FileDeliveryService;
import com.mengde.clouddrive.share.FileShareRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api")
public class FileDeliveryController {
    private final FileDeliveryService delivery; public FileDeliveryController(FileDeliveryService delivery){this.delivery=delivery;}
    @GetMapping("/files/{id}/download") public ResponseEntity<InputStreamResource> owner(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable("id") long id,@RequestHeader(value="Range",required=false) String range)throws IOException{return response(delivery.ownerDownload(user.id(),id,range),range!=null);}
    @PostMapping("/shares") public ResponseEntity<ShareResponse> share(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody CreateShareRequest request){FileShareRow row=delivery.create(user.id(),request.documentId(),request.passcode(),request.expiresAt()); return ResponseEntity.status(HttpStatus.CREATED).body(new ShareResponse(row.getToken(),row.getDocumentId(),row.getExpiresAt()));}
    @GetMapping("/shares/{token}/download") public ResponseEntity<InputStreamResource> shared(@PathVariable("token") String token,@RequestParam("passcode") String passcode,@RequestHeader(value="Range",required=false) String range)throws IOException{return response(delivery.sharedDownload(token,passcode,range),range!=null);}
    private ResponseEntity<InputStreamResource> response(FileDelivery file, boolean partial){HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_OCTET_STREAM);h.setContentLength(file.end()-file.start()+1);h.set(HttpHeaders.ACCEPT_RANGES,"bytes");h.set(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+file.displayName().replace("\"","")+"\"");if(partial)h.set(HttpHeaders.CONTENT_RANGE,"bytes "+file.start()+"-"+file.end()+"/"+file.totalSize());return new ResponseEntity<>(new InputStreamResource(file.input()),h,partial?HttpStatus.PARTIAL_CONTENT:HttpStatus.OK);}
    public record CreateShareRequest(@Positive long documentId,@NotBlank String passcode,LocalDateTime expiresAt){}
    public record ShareResponse(String token,long documentId,LocalDateTime expiresAt){}
}
