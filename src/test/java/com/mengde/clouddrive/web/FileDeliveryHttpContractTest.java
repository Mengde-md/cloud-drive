package com.mengde.clouddrive.web;

import com.mengde.clouddrive.auth.AuthController;
import com.mengde.clouddrive.auth.UserAccountMapper;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.FileShareMapper;
import com.mengde.clouddrive.upload.UploadSessionRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=DriveEntryWebApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=${CLOUDDRIVE_DB_URL:jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}","spring.datasource.username=${CLOUDDRIVE_DB_USERNAME}","spring.datasource.password=${CLOUDDRIVE_DB_PASSWORD}","clouddrive.storage.root=target/day19-test-storage"})
class FileDeliveryHttpContractTest {
    static final Path ROOT=Path.of("target/day19-test-storage");
    @Autowired TestRestTemplate rest; @Autowired DriveEntryRepository entries; @Autowired UserAccountMapper users; @Autowired FileShareMapper shares; @Autowired UploadSessionRepository sessions;
    @BeforeEach @AfterEach void clean() throws IOException {shares.deleteAll();sessions.deleteAll();entries.deleteAll();users.deleteAll();if(Files.exists(ROOT))try(var p=Files.walk(ROOT)){p.sorted(Comparator.reverseOrder()).forEach(x->{try{Files.deleteIfExists(x);}catch(IOException e){throw new RuntimeException(e);}});}}
    @Test void ownerGets206AndShareRequiresCorrectPasscode(){Session alice=session("alice"+System.nanoTime());long id=upload(alice,"range.txt","abcdef".getBytes()).getBody().id();HttpHeaders range=new HttpHeaders();range.putAll(alice.h);range.set("Range","bytes=2-99");ResponseEntity<byte[]> partial=rest.exchange("/api/files/{id}/download",HttpMethod.GET,new HttpEntity<>(range),byte[].class,id);assertAll(()->assertEquals(HttpStatus.PARTIAL_CONTENT,partial.getStatusCode()),()->assertEquals("bytes 2-5/6",partial.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)),()->assertArrayEquals("cdef".getBytes(),partial.getBody()));FileDeliveryController.ShareResponse share=rest.postForEntity("/api/shares",new HttpEntity<>(new FileDeliveryController.CreateShareRequest(id,"2468",null),alice.h),FileDeliveryController.ShareResponse.class).getBody();ResponseEntity<ApiError> denied=rest.getForEntity("/api/shares/{token}/download?passcode=bad",ApiError.class,share.token());ResponseEntity<byte[]> allowed=rest.getForEntity("/api/shares/{token}/download?passcode=2468",byte[].class,share.token());assertAll(()->assertEquals(HttpStatus.NOT_FOUND,denied.getStatusCode()),()->assertEquals(HttpStatus.OK,allowed.getStatusCode()),()->assertArrayEquals("abcdef".getBytes(),allowed.getBody()));}
    private Session session(String n){rest.postForEntity("/api/auth/register",new AuthController.Credentials(n,"correct-password"),AuthController.UserResponse.class);ResponseEntity<AuthController.TokenResponse> login=rest.postForEntity("/api/auth/login",new AuthController.Credentials(n,"correct-password"),AuthController.TokenResponse.class);HttpHeaders h=new HttpHeaders();h.setBearerAuth(login.getBody().accessToken());h.setContentType(MediaType.APPLICATION_JSON);return new Session(h);}
    private ResponseEntity<DocumentUploadController.UploadResponse> upload(Session s,String name,byte[] b){MultiValueMap<String,Object> body=new LinkedMultiValueMap<>();body.add("displayName",name);body.add("file",new ByteArrayResource(b){@Override public String getFilename(){return name;}});HttpHeaders h=new HttpHeaders();h.putAll(s.h);h.setContentType(MediaType.MULTIPART_FORM_DATA);return rest.postForEntity("/api/files/upload",new HttpEntity<>(body,h),DocumentUploadController.UploadResponse.class);}
    private record Session(HttpHeaders h){}
}
