package com.easyshell.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建工作流草稿请求（§6.1 POST /api/v1/workflow）。
 *
 * <p>created_by 一律取当前登录用户，绝不接受请求体指定（与 TaskController.create 同纪律）。</p>
 */
@Data
public class WorkflowCreateRequest {

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    @Size(max = 50)
    private String category;

    /** private(默认) / public */
    @Size(max = 16)
    private String visibility;
}
