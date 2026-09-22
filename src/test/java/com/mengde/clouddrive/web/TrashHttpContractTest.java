package com.mengde.clouddrive.web;

import com.mengde.clouddrive.auth.AuthController;
import com.mengde.clouddrive.auth.UserAccountMapper;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.FileShareMapper;
import com.mengde.clouddrive.upload.UploadSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        classes = DriveEntryWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=${CLOUDDRIVE_DB_URL:jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}",
                "spring.datasource.username=${CLOUDDRIVE_DB_USERNAME:root}",
                "spring.datasource.password=${CLOUDDRIVE_DB_PASSWORD:123456}"
        })
class TrashHttpContractTest {
 @Autowired TestRestTemplate rest; @Autowired DriveEntryRepository entries; @Autowired UserAccountMapper users;
 @Autowired FileShareMapper shares; @Autowired UploadSessionRepository sessions;
 // FK-safe, self-healing cleanup: shares -> sessions -> entries -> users, both
 // before and after each test. This is a real persistent MySQL database shared
 // across the whole mvn test run (and across separate runs), so any earlier
 // class/run that left rows behind must not be able to break this class, and
 // this class must not be able to leave rows behind for the next one.
 @BeforeEach @AfterEach void clean(){ shares.deleteAll(); sessions.deleteAll(); entries.deleteAll(); users.deleteAll(); }
 @Test void ownerCanTrashPageRestoreAndPurgeWithoutExposingAnotherOwner(){
  Session a=session("trash-a"+System.nanoTime()), b=session("trash-b"+System.nanoTime());
  long id=((Number)rest.postForEntity("/api/entries",new HttpEntity<>("{\"displayName\":\"gone\",\"kind\":\"DOCUMENT\",\"byteSize\":1}",a.h),Map.class).getBody().get("id")).longValue();
  assertEquals(HttpStatus.NO_CONTENT,rest.exchange("/api/entries/{id}",HttpMethod.DELETE,new HttpEntity<>(a.h),Void.class,id).getStatusCode());
  assertEquals(HttpStatus.NOT_FOUND,rest.exchange("/api/entries/{id}",HttpMethod.GET,new HttpEntity<>(a.h),ApiError.class,id).getStatusCode());
  assertEquals(1,((java.util.List<?>)rest.exchange("/api/entries/trash",HttpMethod.GET,new HttpEntity<>(a.h),Map.class).getBody().get("items")).size());
  assertEquals(0,((java.util.List<?>)rest.exchange("/api/entries/trash",HttpMethod.GET,new HttpEntity<>(b.h),Map.class).getBody().get("items")).size());
  assertEquals(HttpStatus.NO_CONTENT,rest.postForEntity("/api/entries/trash/{id}/restore",new HttpEntity<>(a.h),Void.class,id).getStatusCode());
 }
 private Session session(String n){var r=rest.postForEntity("/api/auth/register",new AuthController.Credentials(n,"correct-password"),AuthController.UserResponse.class);var l=rest.postForEntity("/api/auth/login",new AuthController.Credentials(n,"correct-password"),AuthController.TokenResponse.class);HttpHeaders h=new HttpHeaders();h.setBearerAuth(l.getBody().accessToken());h.setContentType(MediaType.APPLICATION_JSON);return new Session(h);}
 private record Session(HttpHeaders h){}
}
