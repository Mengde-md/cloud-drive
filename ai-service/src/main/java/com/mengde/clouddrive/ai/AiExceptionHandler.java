package com.mengde.clouddrive.ai;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class AiExceptionHandler {
    @ExceptionHandler(AiFailure.class)
    ResponseEntity<Map<String, Object>> ai(AiFailure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("status", failure.status(), "code", failure.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, Object>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "code", "AI_INPUT_INVALID"));
    }
}
