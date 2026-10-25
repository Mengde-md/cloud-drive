package com.base.common.web;

import com.base.common.Result;
import com.base.common.exception.BusinessException;
import com.base.common.exception.SystemException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ErrorController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void businessFailureReturnsHttp400AndReadableMessage() throws Exception {
        mvc.perform(get("/test/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("密码错误"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void applicationBusinessCodeIsPreservedIndependentlyOfHttpStatus() throws Exception {
        mvc.perform(get("/test/business-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("存储空间不足"));
    }

    @Test
    void beanValidationReturnsHttp400() throws Exception {
        mvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"count\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("文件名不能为空"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "{\"name\":\"notes.txt\",\"count\":\"many\"}"})
    void unreadableJsonReturnsHttp400WithoutParserDetails(String body) throws Exception {
        mvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体不是有效的 JSON 或字段类型不正确"))
                .andExpect(content().string(not(containsString("com.fasterxml"))));
    }

    @Test
    void illegalArgumentReturnsHttp400() throws Exception {
        mvc.perform(get("/test/argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("文件大小必须大于零"));
    }

    @Test
    void systemFailureReturnsHttp500AndHidesInternalDetails() throws Exception {
        mvc.perform(get("/test/system"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("系统异常，请稍后重试"))
                .andExpect(content().string(not(containsString("private-diagnostic"))));
    }

    @Test
    void applicationSystemCodeIsPreservedWithoutExposingDiagnostics() throws Exception {
        mvc.perform(get("/test/system-code"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("系统异常，请稍后重试"));
    }

    @Test
    void unexpectedFailureReturnsHttp500AndGenericMessage() throws Exception {
        mvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("系统异常，请稍后重试"))
                .andExpect(content().string(not(containsString("private-diagnostic"))));
    }

    @Test
    void validRequestStillReturnsHttp200AndSuccessEnvelope() throws Exception {
        mvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"notes.txt\",\"count\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").value("notes.txt"));
    }

    record Input(@NotBlank(message = "文件名不能为空") String name, int count) { }

    @RestController
    static class ErrorController {
        @GetMapping("/test/business")
        Result<?> business() { throw new BusinessException("密码错误"); }

        @GetMapping("/test/business-code")
        Result<?> businessCode() { throw new BusinessException(10001, "存储空间不足"); }

        @PostMapping("/test/input")
        Result<?> input(@Valid @RequestBody Input input) { return Result.success(input.name()); }

        @GetMapping("/test/argument")
        Result<?> argument() { throw new IllegalArgumentException("文件大小必须大于零"); }

        @GetMapping("/test/system")
        Result<?> system() { throw new SystemException("private-diagnostic: storage operation failed"); }

        @GetMapping("/test/system-code")
        Result<?> systemCode() { throw new SystemException(50001, "private-diagnostic: storage operation failed"); }

        @GetMapping("/test/unexpected")
        Result<?> unexpected() { throw new IllegalStateException("private-diagnostic: unexpected failure"); }
    }
}
