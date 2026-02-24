package com.easyshell.server.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserUpdateRequest {

    @Size(max = 128, message = "邮箱长度不超过128个字符")
    private String email;

    private String role;

    private Integer status;
}
