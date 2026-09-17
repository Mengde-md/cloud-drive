package com.mengde.clouddrive.web;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不启动 HTTP 端口，直接证明 Day06 DTO 的 Bean Validation 约束真实可执行。
 * HTTP 层仍由 DriveEntryHttpContractTest 覆盖；本类防止注解只停留在源码外观上。
 */
class RequestValidationContractTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void createRequestRejectsEachInvalidTransportField() {
        EntryCreateRequest request = new EntryCreateRequest(0L, "  ", null, -1L);

        Set<String> invalidFields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("parentId", "displayName", "kind", "byteSize"), invalidFields);
    }

    @Test
    void validCreateRequestAndRenameRequestPassBeanValidation() {
        EntryCreateRequest createRequest = new EntryCreateRequest(null, "有效文档", "DOCUMENT", 0L);
        RenameRequest renameRequest = new RenameRequest("新名称");

        assertAll(
                () -> assertTrue(validator.validate(createRequest).isEmpty()),
                () -> assertTrue(validator.validate(renameRequest).isEmpty())
        );
    }

    @Test
    void renameRequestRejectsBlankDisplayName() {
        Set<String> invalidFields = validator.validate(new RenameRequest(" \t ")).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("displayName"), invalidFields);
    }
}
