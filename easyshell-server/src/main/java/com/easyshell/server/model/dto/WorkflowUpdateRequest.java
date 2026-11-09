package com.easyshell.server.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改工作流元信息请求（§6.1 PUT /api/v1/workflow/{id}）。
 *
 * <p>只改元信息（name/description/category/visibility）；定义变更必须走版本端点，
 * 保证 {@code workflow_version} 是不可变指纹载体（§5.2 / §7.2.1）。</p>
 */
@Data
public class WorkflowUpdateRequest {

    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    @Size(max = 50)
    private String category;

    /** private / public */
    @Size(max = 16)
    private String visibility;
}
