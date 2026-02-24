package com.easyshell.server.ai.tool;

import com.easyshell.server.model.dto.ScriptRequest;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.service.ScriptService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ScriptManageTool {

    private final ScriptService scriptService;

    @Tool(description = "查询脚本库中的所有脚本列表，包括名称、类型、描述等信息")
    public String listScripts() {
        List<Script> scripts = scriptService.findAll();
        if (scripts.isEmpty()) {
            return "脚本库中暂无脚本";
        }

        StringBuilder sb = new StringBuilder("脚本列表:\n");
        for (Script s : scripts) {
            sb.append(String.format("- [ID:%d] %s | 类型: %s | 描述: %s\n",
                    s.getId(),
                    s.getName(),
                    s.getScriptType(),
                    s.getDescription() != null ? s.getDescription() : "无"));
        }
        return sb.toString();
    }

    @Tool(description = "查询指定脚本的详细信息，包括完整的脚本内容")
    public String getScriptDetail(@ToolParam(description = "脚本 ID") Long id) {
        return scriptService.findById(id)
                .map(s -> String.format("脚本详情:\n名称: %s\nID: %d\n类型: %s\n描述: %s\n是否公开: %s\n创建时间: %s\n\n脚本内容:\n%s",
                        s.getName(), s.getId(), s.getScriptType(),
                        s.getDescription() != null ? s.getDescription() : "无",
                        s.getIsPublic() != null && s.getIsPublic() ? "是" : "否",
                        s.getCreatedAt(),
                        s.getContent()))
                .orElse("未找到 ID 为 " + id + " 的脚本");
    }

    @Tool(description = "创建一个新的脚本并保存到脚本库中")
    public String createScript(
            @ToolParam(description = "脚本名称") String name,
            @ToolParam(description = "脚本内容") String content,
            @ToolParam(description = "脚本描述") String description) {
        try {
            ScriptRequest request = new ScriptRequest();
            request.setName(name);
            request.setContent(content);
            request.setDescription(description);
            request.setScriptType("shell");
            request.setIsPublic(true);

            Script script = scriptService.create(request, 1L);
            return String.format("脚本创建成功！ID: %d, 名称: %s", script.getId(), script.getName());
        } catch (Exception e) {
            return "创建脚本失败: " + e.getMessage();
        }
    }
}
