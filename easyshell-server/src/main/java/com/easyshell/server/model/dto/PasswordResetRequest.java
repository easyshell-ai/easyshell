package com.easyshell.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PasswordResetRequest {

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 128, message = "密码长度为6-128个字符")
    private String newPassword;
}
