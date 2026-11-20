package com.easyshell.server.model.vo;

import com.easyshell.server.model.entity.WorkflowVersion;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 单个版本的完整内容（§6.1 GET /{id}/versions/{versionId}、PUT /{id}/versions/draft 的响应体，
 * 以及 GET /{id} 详情里的 {@code draft} 字段）。
 *
 * <p><b>形状是平的</b>：版本摘要字段与 {@code definition} 同级，不再多嵌一层 {@code version}。
 * 理由是编辑器把"草稿行"当一个对象用——画布要 {@code definition}、乐观锁要 {@code baseVersion}、
 * 版本列要 {@code version}，全都从同一层取；嵌一层会让每个调用点都多一次解引用，且
 * 与 {@code WorkflowVersionVO}（列表/保存/发布三个端点共用）逐渐漂移。前端
 * {@code types.ts} 的 {@code WorkflowVersionDetail} 就是这个形状，两边以此处为权威。</p>
 *
 * <p>definition 用 {@code JsonNode} 原样透出（未知字段已在入口被服务端 schema 拒绝），
 * 不在这里二次建模。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkflowVersionDetailVO extends WorkflowVersionVO {

    private JsonNode definition;

    public static WorkflowVersionDetailVO from(WorkflowVersion version, JsonNode definition) {
        WorkflowVersionDetailVO vo = new WorkflowVersionDetailVO();
        vo.setId(version.getId());
        vo.setVersion(version.getVersion());
        vo.setBaseVersion(version.getBaseVersion());
        vo.setIsValid(version.getIsValid());
        vo.setEstimatedRisk(version.getEstimatedRisk());
        vo.setChangelog(version.getChangelog());
        vo.setDefinitionSha256(version.getDefinitionSha256());
        vo.setPublishedAt(version.getPublishedAt());
        vo.setPublishedBy(version.getPublishedBy());
        vo.setCreatedAt(version.getCreatedAt());
        vo.setDefinition(definition);
        return vo;
    }
}
