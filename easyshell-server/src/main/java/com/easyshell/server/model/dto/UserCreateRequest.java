package com.easyshell.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserCreateRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 64, message = "用户名长度为3-64个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 128, message = "密码长度为6-128个字符")
    private String password;

    @Size(max = 128, message = "邮箱长度不超过128个字符")
    private String email;

    @NotBlank(message = "角色不能为空")
    private String role = "viewer";
}
