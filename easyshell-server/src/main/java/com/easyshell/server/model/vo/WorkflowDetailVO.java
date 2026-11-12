package com.easyshell.server.model.vo;

import com.easyshell.server.model.entity.Workflow;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工作流详情（§6.1 GET /{id}：编辑器首屏一次拿全，避免三个请求竞态）。
 *
 * <p><b>为什么是复合体而不是平铺实体</b>：编辑器打开一个工作流需要三样东西——元信息
 * （名称/状态/归属）、版本列表（版本列与"发布历史"）、当前草稿行（画布内容 + 乐观锁令牌）。
 * 设计 §4.3 的状态同步链路以"加载草稿"为起点，若详情只返回实体，前端就得再打
 * {@code /versions} 与草稿两个请求，且发布后刷新会出现"草稿还没建好"的中间态。
 * {@code draft} 为 null = 该工作流当前没有草稿行（发布后首次编辑前，G-14）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDetailVO {

    private Workflow workflow;

    /** 版本摘要列表（version 倒序，不含 definitionJson） */
    private List<WorkflowVersionVO> versions;

    /** 当前草稿行（摘要 + definition）；不存在时为 null */
    private WorkflowVersionDetailVO draft;
}
