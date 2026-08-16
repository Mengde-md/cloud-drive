package com.base.auth.param;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterParam {
    @NotBlank @Email
    private String email;
    @NotBlank @Size(min = 6, max = 64)
    private String password;
    @NotBlank @Size(min = 1, max = 50)
    private String nickName;
}
