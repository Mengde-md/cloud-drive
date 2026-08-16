package com.base.auth.param;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginParam {
    @NotBlank @Email
    private String email;
    @NotBlank
    private String password;
}
