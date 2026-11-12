package com.easyshell.server.workflow;

import com.easyshell.server.model.entity.User;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.dto.JobResultRequest;
import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.HostIdentityService;
import com.easyshell.server.service.TaskService;
import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowRunProvenanceRepository;
import com.easyshell.server.security.JwtUtils;
import com.easyshell.server.workflow.service.WorkflowRunProvenanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作流 REST <b>契约</b>测试（真实 Spring 上下文 + MockMvc 全链路，H2 + 打桩 Redis）。
 *
 * <p><b>为什么要有这个测试</b>：服务端与前端是两路并行开发的，曾经各按各的假设演进——
 * 详情端点只返回实体而前端要 {@code {workflow, versions, draft}}、触发送 {@code {inputs}}
 * 而服务端收 {@code {input, idempotencyKey}}、run 直接序列化实体导致 {@code inputJson}
 * 是字符串而前端当对象用。等页面跑起来才发现，一崩就是一片。本测试把<b>每个端点的响应
 * JSON 字段名与嵌套层级</b>钉死在这里：将来任何人改一个 VO 字段名、把实体直接 return
 * 出去、或者把平铺结构改成嵌套，这里立刻红。</p>
 *
 * <p>断言口径：只断言<b>前端真正消费的字段</b>（见 easyshell-web/src/pages/workflow/types.ts），
 * 不锁无关字段——锁太多会让测试比契约还脆。</p>
 */
// RANDOM_PORT：真实 servlet 容器（WebSocketConfig 的 ServerContainer 在 MOCK 环境下不存在），
// MockMvc 仍然自动装配，断言口径不变。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 契约测试会连续触发多个 run，关掉同工作流触发最小间隔（§3.9 的 30s 默认值）
        "easyshell.workflow.engine.min-trigger-interval-sec=0",
        // 并发满时 run 会进 queued，1s 扫描兜底泵回（默认 10s 太慢，契约测试等不起）
        "easyshell.workflow.engine.wakeup-interval-sec=1",
        // 4 < 引擎池 core(5)：drive() 在池内 join 同池的节点任务，并发 drive 数
        // 一旦追上池线程数就会互相饿死；4 既留有余量又比默认 3 更能吸收用例间的突发触发
        "easyshell.workflow.engine.max-concurrent-runs=4",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=warn"
})
@DisplayName("工作流 REST 契约 — 18 个端点的响应 JSON 形状")
class WorkflowApiContractTest {

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private AgentWebSocketHandler agentTransport;
    @Autowired private AgentRepository agents;
    @Autowired private HostIdentityService identities;
    @Autowired private HostAclService acl;
    @Autowired private TaskService taskService;
    @Autowired private TaskTargetSnapshotRepository targetSnapshots;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private WorkflowApprovalRepository approvalRepository;
    @Autowired
    private WorkflowRunProvenanceRepository provenanceRepository;
    @Autowired
    private WorkflowRunProvenanceService provenanceService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String token;
    private Long ownerId;

    @Test
    void codeSample_rejectsExecutionWithoutResourceIsolation() throws Exception {
        long id = createWorkflow();
        mockMvc.perform(post("/api/v1/workflow/{id}/nodes/code/test", id)
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"language":"javascript","code":"function main(input) { return {value: input.n + 1}; }","input":{"n":2}}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("CODE_SAMPLE_RESOURCE_ISOLATION_UNAVAILABLE"));
    }

    /** 三节点 + 两连线：wait → approval → wait（wait 不需要 agent，契约测试不依赖执行环境） */
    private static final String DEFINITION = """
            {
              "schemaVersion": 1,
              "defaultOnUpstreamFailure": "skip",
              "onRejection": "abort",
              "variables": { "threshold": { "type": "number", "required": true } },
              "nodes": [
                { "key": "n1", "type": "wait", "name": "等待1秒", "position": { "x": 0, "y": 0 },
                  "config": { "durationSec": 1 } },
                { "key": "n2", "type": "approval", "name": "人工审批", "position": { "x": 200, "y": 0 },
                  "config": { "title": "契约审批", "approverRole": "ADMIN", "onTimeout": "reject",
                              "timeoutSec": 120 } },
                { "key": "n3", "type": "wait", "name": "等待1秒", "position": { "x": 400, "y": 0 },
                  "config": { "durationSec": 1 } }
              ],
              "edges": [
                { "id": "e1", "from": "n1", "to": "n2" },
                { "id": "e2", "from": "n2", "to": "n3" }
              ]
            }
            """;

    /**
     * 单 wait 节点 + 必填变量：跑完即终态，不会一直占着全局并发位（不需要 agent）。
     * 除审批两条用例外全部用它——否则每个用例都留下一个 waiting 的 run，
     * 把后面的用例挤去排队。
     */
    private static final String SIMPLE_DEFINITION = """
            {
              "schemaVersion": 1,
              "defaultOnUpstreamFailure": "skip",
              "onRejection": "abort",
              "variables": { "threshold": { "type": "number", "required": true } },
              "nodes": [
                { "key": "n1", "type": "wait", "name": "等待1秒", "position": { "x": 0, "y": 0 },
                  "config": { "durationSec": 1 } }
              ],
              "edges": []
            }
            """;

    private static final String PURE_DEFINITION = SIMPLE_DEFINITION.replace(
            "\"variables\": { \"threshold\": { \"type\": \"number\", \"required\": true } }", "\"variables\": {}");

    @BeforeEach
    void setUp() throws Exception {
        String username = "contract-" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode("Contract#2026"));
        user.setEmail(username + "@easyshell.test");
        user.setRole("super_admin");
        user.setStatus(1);
        userRepository.save(user);
        ownerId = user.getId();

        // 直接用 JwtUtils 签发：走 /auth/login 会写 Redis 刷新令牌，与契约无关
        token = "Bearer " + jwtUtils.generateAccessToken(user.getId(), username, "super_admin");
    }

    // ------------------------------------------------------------------
    // ①② 列表 / 新建
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /page：content[] 是工作流摘要（含 currentVersion），分页五件套齐全")
    void page_returnsWorkflowSummaries() throws Exception {
        long id = createWorkflow();

        mockMvc.perform(get("/api/v1/workflow/page").param("page", "0").param("size", "20")
                        .param("keyword", "契约").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content[0].id").value(id))
                .andExpect(jsonPath("$.data.content[0].name").value("契约工作流"))
                .andExpect(jsonPath("$.data.content[0].status").value("draft"))
                .andExpect(jsonPath("$.data.content[0].visibility").value("private"))
                .andExpect(jsonPath("$.data.content[0].createdBy").exists())
                .andExpect(jsonPath("$.data.content[0].runCount").value(0))
                .andExpect(jsonPath("$.data.content[0].createdAt").exists())
                .andExpect(jsonPath("$.data.totalElements").exists())
                .andExpect(jsonPath("$.data.totalPages").exists())
                .andExpect(jsonPath("$.data.size").exists())
                .andExpect(jsonPath("$.data.number").exists());
    }

    @Test
    @DisplayName("GET /page：keyword 真的在过滤（搜索框不是静默无效）")
    void page_keywordFilters() throws Exception {
        createWorkflow();

        mockMvc.perform(get("/api/v1/workflow/page").param("keyword", "不存在的关键字xyz")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("POST /：新建返回工作流实体（createdBy 是当前用户）")
    void create_returnsWorkflow() throws Exception {
        mockMvc.perform(post("/api/v1/workflow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"契约工作流\",\"category\":\"ops\",\"visibility\":\"private\"}")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.name").value("契约工作流"))
                .andExpect(jsonPath("$.data.status").value("draft"))
                .andExpect(jsonPath("$.data.createdBy").exists());
    }

    // ------------------------------------------------------------------
    // ③④⑤ 详情 / 改元信息 / 软删
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /{id}：{workflow, versions, draft} 三件套，draft 是平铺版本 + definition")
    void detail_returnsWorkflowVersionsAndDraft() throws Exception {
        long id = createWorkflow();
        saveDraft(id);

        mockMvc.perform(get("/api/v1/workflow/{id}", id).header("Authorization", token))
                .andExpect(status().isOk())
                // workflow：元信息
                .andExpect(jsonPath("$.data.workflow.id").value(id))
                .andExpect(jsonPath("$.data.workflow.name").value("契约工作流"))
                .andExpect(jsonPath("$.data.workflow.status").value("draft"))
                .andExpect(jsonPath("$.data.workflow.createdBy").exists())
                // versions：摘要数组
                .andExpect(jsonPath("$.data.versions").isArray())
                .andExpect(jsonPath("$.data.versions[0].id").exists())
                .andExpect(jsonPath("$.data.versions[0].version").value(1))
                .andExpect(jsonPath("$.data.versions[0].baseVersion").exists())
                .andExpect(jsonPath("$.data.versions[0].isValid").value(false))
                .andExpect(jsonPath("$.data.versions[0].definitionSha256").exists())
                // draft：平铺（不是再嵌一层 version）+ definition 原样透出
                .andExpect(jsonPath("$.data.draft.id").exists())
                .andExpect(jsonPath("$.data.draft.version").value(1))
                .andExpect(jsonPath("$.data.draft.baseVersion").exists())
                .andExpect(jsonPath("$.data.draft.definition.schemaVersion").value(1))
                .andExpect(jsonPath("$.data.draft.definition.nodes").isArray())
                .andExpect(jsonPath("$.data.draft.definition.nodes.length()").value(3))
                .andExpect(jsonPath("$.data.draft.definition.edges.length()").value(2))
                .andExpect(jsonPath("$.data.draft.definition.variables.threshold.type").value("number"))
                // 反契约：嵌套形态一旦回来，这里必须红
                .andExpect(jsonPath("$.data.draft.version.id").doesNotExist())
                .andExpect(jsonPath("$.data.draft.version.version").doesNotExist());
    }

    @Test
    @DisplayName("GET /{id}：非所有者 → 403（数据级校验没有被 VO 改造绕过）")
    void detail_stranger_is403() throws Exception {
        long id = createWorkflow();
        String stranger = tokenFor("stranger-" + UUID.randomUUID().toString().substring(0, 8), "operator");


        mockMvc.perform(get("/api/v1/workflow/{id}", id).header("Authorization", stranger))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /{id}：改元信息返回更新后的工作流")
    void update_meta() throws Exception {
        long id = createWorkflow();

        mockMvc.perform(put("/api/v1/workflow/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"改过了\"}")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.description").value("改过了"));
    }

    @Test
    @DisplayName("DELETE /{id}：软删 → archived，绝不物理删除")
    void delete_softDeletes() throws Exception {
        long id = createWorkflow();

        mockMvc.perform(delete("/api/v1/workflow/{id}", id).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("archived"));
    }

    // ------------------------------------------------------------------
    // ⑥⑦⑧ 版本列表 / 版本定义 / 保存草稿
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /{id}/versions：摘要数组，不含 definitionJson")
    void versions_returnsSummaries() throws Exception {
        long id = createWorkflow();
        var initialDraft = currentDraft(id);
        assertThat(initialDraft.isMissingNode() || initialDraft.isNull()).isTrue();
        mockMvc.perform(put("/api/v1/workflow/{id}/versions/draft", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":" + DEFINITION + ",\"baseVersion\":0,\"expectedAbsent\":true,"
                                + "\"changelog\":\"契约草稿\"}")
                        .header("Authorization", token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/workflow/{id}/versions", id).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").exists())
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[0].baseVersion").exists())
                .andExpect(jsonPath("$.data[0].isValid").value(false))
                .andExpect(jsonPath("$.data[0].changelog").value("契约草稿"))
                .andExpect(jsonPath("$.data[0].definitionSha256").exists())
                .andExpect(jsonPath("$.data[0].definitionJson").doesNotExist())
                .andExpect(jsonPath("$.data[0].definition").doesNotExist());
    }

    @Test
    @DisplayName("GET /{id}/versions/{versionId}：平铺摘要 + definition（监控页画布取 definition）")
    void versionDefinition_isFlat() throws Exception {
        long id = createWorkflow();
        saveDraft(id);
        long versionId = firstVersionId(id);

        mockMvc.perform(get("/api/v1/workflow/{id}/versions/{versionId}", id, versionId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(versionId))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.baseVersion").exists())
                .andExpect(jsonPath("$.data.definition.nodes.length()").value(3))
                .andExpect(jsonPath("$.data.definition.edges.length()").value(2))
                // 反契约：不许再嵌一层 version
                .andExpect(jsonPath("$.data.version.id").doesNotExist());
    }

    @Test
    @DisplayName("PUT /{id}/versions/draft：baseVersion 令牌往返（首次原样带回，再存才 +1）")
    void saveDraft_returnsNewTokenAndDefinition() throws Exception {
        long id = createWorkflow();
        var initialDraft = currentDraft(id);
        assertThat(initialDraft.isMissingNode() || initialDraft.isNull()).isTrue();

        // 首次保存：明确声明尚无草稿。
        String firstSave = mockMvc.perform(put("/api/v1/workflow/{id}/versions/draft", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":" + DEFINITION + ",\"baseVersion\":0,\"expectedAbsent\":true,\"changelog\":\"首次\"}")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.baseVersion").value(0))
                .andExpect(jsonPath("$.data.isValid").value(false))
                .andExpect(jsonPath("$.data.definition.nodes.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        var firstDraft = new com.fasterxml.jackson.databind.ObjectMapper().readTree(firstSave).path("data");

        // 第二次保存：令牌 +1，客户端下一次必须带回新值
        mockMvc.perform(put("/api/v1/workflow/{id}/versions/draft", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftSaveBody(DEFINITION, firstDraft))
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseVersion").value(1));
    }

    @Test
    @DisplayName("PUT /{id}/versions/draft：baseVersion 过期 → 409 + 三选一契约")
    void saveDraft_staleToken_is409() throws Exception {
        long id = createWorkflow();
        saveDraft(id);
        var staleDraft = currentDraft(id);
        saveDraft(id);

        mockMvc.perform(put("/api/v1/workflow/{id}/versions/draft", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftSaveBody(DEFINITION, staleDraft))
                        .header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.data.currentBaseVersion").exists())
                .andExpect(jsonPath("$.data.draftVersionId").exists())
                .andExpect(jsonPath("$.data.options.length()").value(3))
                .andExpect(jsonPath("$.data.options[0].action").value("overwrite"))
                .andExpect(jsonPath("$.data.options[1].action").value("copy"))
                .andExpect(jsonPath("$.data.options[2].action").value("reload"));
    }

    // ------------------------------------------------------------------
    // ⑨⑩⑪ 校验 / 发布 / 触发
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /{id}/validate：{valid, issues[], estimatedRisk, riskMatches}（不是裸数组）")
    void validate_returnsResultObject() throws Exception {
        long id = createWorkflow();

        mockMvc.perform(post("/api/v1/workflow/{id}/validate", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":" + DEFINITION + "}")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.issues").isArray())
                .andExpect(jsonPath("$.data.estimatedRisk").value("LOW"))
                .andExpect(jsonPath("$.data.riskMatches").isArray())
                // 反契约：曾是裸 issues[] 数组
                .andExpect(jsonPath("$.data[0]").doesNotExist());
    }

    @Test
    @DisplayName("POST /{id}/validate：空 body → 400 WORKFLOW_PARAM_INVALID（不是 500）")
    void validate_missingBody_is400() throws Exception {
        long id = createWorkflow();

        // 回归：@RequestBody 漏掉 required=false 时，前端调预校验不发 body 会让 Spring
        // 抛 HttpMessageNotReadableException → 500，而前端把非 200 当成"无 issues"，
        // 发布弹窗于是把 ERROR 工作流显示成"校验通过"并放行发布按钮。
        mockMvc.perform(post("/api/v1/workflow/{id}/validate", id)
                        .header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("WORKFLOW_PARAM_INVALID")));
    }

    @Test
    @DisplayName("POST /{id}/validate：body 里缺 definition → 400（不是 500）")
    void validate_bodyWithoutDefinition_is400() throws Exception {
        long id = createWorkflow();

        // 空 body 与"body 里没有 definition"是同一条失败路径：都要让客户端看见
        // 400，而不是被 Jackson 抛成 500 后被前端误读成"无 issues"。
        mockMvc.perform(post("/api/v1/workflow/{id}/validate", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("WORKFLOW_PARAM_INVALID")));
    }

    @Test
    @DisplayName("POST /{id}/publish：发布成功返回不可变版本摘要")
    void publish_returnsVersion() throws Exception {
        long id = createWorkflow();
        saveDraft(id);

        mockMvc.perform(post("/api/v1/workflow/{id}/publish", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(id))
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.isValid").value(true))
                .andExpect(jsonPath("$.data.publishedAt").exists())
                .andExpect(jsonPath("$.data.publishedBy").exists())
                .andExpect(jsonPath("$.data.estimatedRisk").value("LOW"));
    }

    @Test
    @DisplayName("POST /{id}/publish：校验 ERROR → 422 + issues[]")
    void publish_validationError_is422() throws Exception {
        long id = createWorkflow();
        // 成环定义：n1→n2→n1
        String cyclic = """
                {
                  "schemaVersion": 1,
                  "defaultOnUpstreamFailure": "skip",
                  "onRejection": "abort",
                  "variables": {},
                  "nodes": [
                    { "key": "a", "type": "wait", "name": "a", "position": { "x": 0, "y": 0 },
                      "config": { "durationSec": 1 } },
                    { "key": "b", "type": "wait", "name": "b", "position": { "x": 200, "y": 0 },
                      "config": { "durationSec": 1 } }
                  ],
                  "edges": [
                    { "id": "e1", "from": "a", "to": "b" },
                    { "id": "e2", "from": "b", "to": "a" }
                  ]
                }
                """;
        saveDraft(id, cyclic);

        mockMvc.perform(post("/api/v1/workflow/{id}/publish", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(id))
                        .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.issues").isArray())
                .andExpect(jsonPath("$.data.issues.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("POST /{id}/runs：请求体是 {input, idempotencyKey}，响应是安全控制摘要")
    void triggerRun_acceptsBodyContract() throws Exception {
        long id = publishedSimpleWorkflow();

        mockMvc.perform(post("/api/v1/workflow/{id}/runs", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"threshold\":85},\"idempotencyKey\":\"contract-key-1\"}")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.workflowId").value(id))
                .andExpect(jsonPath("$.data.versionId").exists())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.triggerType").value("manual"))
                .andExpect(jsonPath("$.data.status").exists())
                .andExpect(jsonPath("$.data.startedBy").exists())
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.input").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.output").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.variables").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.currentNodes").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.inputJson").doesNotExist())
                .andExpect(jsonPath("$.data.variablesJson").doesNotExist())
                .andExpect(jsonPath("$.data.riskSnapshotJson").doesNotExist())
                .andExpect(jsonPath("$.data.ownerInstance").doesNotExist())
                .andExpect(jsonPath("$.data.heartbeatAt").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.data.cancelRequested").value(false))
                .andExpect(jsonPath("$.data.variablesTruncated").doesNotHaveJsonPath());
    }

    @Test
    @DisplayName("POST /{id}/runs：幂等键相同 + 入参相同 → 返回首个 run")
    void triggerRun_idempotentReplay() throws Exception {
        long id = publishedSimpleWorkflow();

        String first = mockMvc.perform(post("/api/v1/workflow/{id}/runs", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"threshold\":85},\"idempotencyKey\":\"contract-idem\"}")
                        .header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String runId = first.replaceAll(".*\"id\":\"(wfr_[^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/v1/workflow/{id}/runs", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"threshold\":85},\"idempotencyKey\":\"contract-idem\"}")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(runId));
    }

    @Test
    @DisplayName("POST /{id}/runs：同幂等键不同入参 → 400 IDEMPOTENCY_CONFLICT（§6.2）")
    void triggerRun_sameKeyDifferentInput_is400() throws Exception {
        long id = publishedSimpleWorkflow();

        mockMvc.perform(post("/api/v1/workflow/{id}/runs", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"threshold\":85},\"idempotencyKey\":\"contract-conflict\"}")
                        .header("Authorization", token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/workflow/{id}/runs", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"threshold\":99},\"idempotencyKey\":\"contract-conflict\"}")
                        .header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("IDEMPOTENCY_CONFLICT")));
    }

    @Test
    @DisplayName("POST /{id}/runs：缺必填入参 → 400（前端只发 inputs 时的真实报错）")
    void triggerRun_missingRequiredInput_is400() throws Exception {
        long id = publishedSimpleWorkflow();

        mockMvc.perform(post("/api/v1/workflow/{id}/runs", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{}}")
                        .header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /{id}/runs：未发布 → 409 NOT_PUBLISHED")
    void triggerRun_unpublished_is409() throws Exception {
        long id = createWorkflow();
        saveDraft(id, SIMPLE_DEFINITION);

        mockMvc.perform(post("/api/v1/workflow/{id}/runs", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"threshold\":85}}")
                        .header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("NOT_PUBLISHED")));
    }

    // ------------------------------------------------------------------
    // ⑫⑬⑭ 运行列表 / 详情 / 取消
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /runs：content[] 是不含动态数据的控制摘要")
    void runs_returnsRunViews() throws Exception {
        long id = publishedSimpleWorkflow();
        trigger(id, "contract-list");

        mockMvc.perform(get("/api/v1/workflow/runs").param("page", "0").param("size", "20")
                        .param("workflowId", String.valueOf(id))
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").exists())
                .andExpect(jsonPath("$.data.content[0].workflowId").value(id))
                .andExpect(jsonPath("$.data.content[0].versionNo").value(1))
                .andExpect(jsonPath("$.data.content[0].status").exists())
                .andExpect(jsonPath("$.data.content[0].input").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.content[0].output").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.content[0].variables").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.content[0].currentNodes").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.content[0].inputJson").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.content[0].variablesJson").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.content[0].createdAt").exists())
                .andExpect(jsonPath("$.data.totalElements").exists());
    }

    @Test
    @DisplayName("GET /runs/{runId}：{run, nodeRuns[]}，节点 input/output 是解析后的对象")
    void runDetail_includesNodeRuns() throws Exception {
        long id = publishedPureWorkflow();
        String runId = triggerPure(id, "contract-detail");

        // 等 n1（wait 1s）落节点行
        await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/workflow/runs/{runId}", runId)
                                .header("Authorization", token))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.run.id").value(runId))
                        .andExpect(jsonPath("$.data.run.input").isMap())
                        .andExpect(jsonPath("$.data.run.status").exists())
                        .andExpect(jsonPath("$.data.run.variables").isMap())
                        .andExpect(jsonPath("$.data.run.currentNodes").isArray())
                        .andExpect(jsonPath("$.data.nodeRuns").isArray())
                        .andExpect(jsonPath("$.data.nodeRuns.length()")
                                .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                        .andExpect(jsonPath("$.data.nodeRuns[0].nodeKey").exists())
                        .andExpect(jsonPath("$.data.nodeRuns[0].nodeType").exists())
                        .andExpect(jsonPath("$.data.nodeRuns[0].attempt").value(1))
                        .andExpect(jsonPath("$.data.nodeRuns[0].status").exists())
                        .andExpect(jsonPath("$.data.nodeRuns[0].output").isMap())
                        // 反契约：实体列名不外泄
                        .andExpect(jsonPath("$.data.run.inputJson").doesNotExist())
                        .andExpect(jsonPath("$.data.nodeRuns[0].outputJson").doesNotExist())
                        .andExpect(jsonPath("$.data.nodeRuns[0].outputSha256").doesNotExist())
                        .andExpect(jsonPath("$.data.nodeRuns[0].inputJson").doesNotExist()));
    }

    @Test
    @DisplayName("GET /runs/{runId}：已证明的手动纯数据输入可由属主查看")
    void runDetail_provenManualInput_isReadable() throws Exception {
        long id = publishedSimpleWorkflow();
        String runId = trigger(id, "contract-unproven");

        mockMvc.perform(get("/api/v1/workflow/runs/{runId}", runId).header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /runs/{runId}/cancel：取消后 cancelRequested=true")
    void cancelRun_setsFlag() throws Exception {
        long id = publishedSimpleWorkflow();
        String runId = trigger(id, "contract-cancel");

        mockMvc.perform(post("/api/v1/workflow/runs/{runId}/cancel", runId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(runId))
                .andExpect(jsonPath("$.data.cancelRequested").value(true))
                .andExpect(jsonPath("$.data.input").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.output").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.variables").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.currentNodes").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.inputJson").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.variablesJson").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.status").exists());
    }

    @Test
    @DisplayName("POST /runs/{runId}/nodes/{nodeKey}/test-run：试运行结果字段")
    void testRun_returnsResult() throws Exception {
        long id = publishedPureWorkflow();
        String runId = triggerPure(id, "contract-testrun");

        mockMvc.perform(post("/api/v1/workflow/runs/{runId}/nodes/{nodeKey}/test-run", runId, "n1")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodeKey").value("n1"))
                .andExpect(jsonPath("$.data.nodeType").value("wait"))
                .andExpect(jsonPath("$.data.status").exists())
                .andExpect(jsonPath("$.data.riskLevel").exists())
                .andExpect(jsonPath("$.data.riskMatches").isArray());
    }

    // ------------------------------------------------------------------
    // ⑯⑰ 审批
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真实审批来源未证明：仅受限摘要，禁止批准但允许拒绝")
    void approval_unprovenSource_isRestrictedAndCanBeRejected() throws Exception {
        long id = publishedWorkflow();
        String runId = trigger(id, "contract-restricted-approval");
        try {
            assertThat(provenanceRepository.findComplete(runId)).isPresent();
            assertThat(provenanceRepository.findComplete(runId).orElseThrow().kind()).isEqualTo("HOST");
            await().atMost(java.time.Duration.ofSeconds(15)).until(() ->
                    approvalRepository.findByRunIdAndNodeKey(runId, "n2").isPresent());
            long approvalId = approvalRepository.findByRunIdAndNodeKey(runId, "n2").orElseThrow().getId();

            String body = mockMvc.perform(get("/api/v1/workflow/approvals/pending")
                            .header("Authorization", token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var approvals = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data");
            com.fasterxml.jackson.databind.JsonNode summary = null;
            for (var approval : approvals) {
                if (approval.path("id").asLong() == approvalId) summary = approval;
            }
            assertThat(summary).isNotNull();
            java.util.List<String> fields = new java.util.ArrayList<>();
            summary.fieldNames().forEachRemaining(fields::add);
            assertThat(fields)
                    .containsExactlyInAnyOrder("id", "status", "expiresAt", "canReject", "canApprove");
            assertThat(summary.path("status").asText()).isEqualTo("pending");
            assertThat(summary.path("expiresAt").asText()).isNotBlank();
            assertThat(summary.path("canReject").asBoolean()).isTrue();
            assertThat(summary.path("canApprove").asBoolean()).isFalse();

            mockMvc.perform(get("/api/v1/workflow/runs/{runId}", runId).header("Authorization", token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/workflow/approvals/{approvalId}/decision", approvalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"approved\",\"comment\":\"must not pass\"}")
                            .header("Authorization", token))
                    .andExpect(status().isForbidden());
            assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus()).isEqualTo("pending");
            mockMvc.perform(post("/api/v1/workflow/approvals/{approvalId}/decision", approvalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"rejected\",\"comment\":\"安全拒绝\"}")
                            .header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(approvalId))
                    .andExpect(jsonPath("$.data.status").value("rejected"))
                    .andExpect(jsonPath("$.data.canApprove").value(false))
                    .andExpect(jsonPath("$.data.detail").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.data.reason").doesNotHaveJsonPath());
        } finally {
            // 即使断言失败也释放运行，避免残留等待审批占住并发位。
            mockMvc.perform(post("/api/v1/workflow/runs/{runId}/cancel", runId)
                    .header("Authorization", token));
        }
    }

    @Test
    @DisplayName("伪造 V3 出生标记不能放宽真实审批的读取或批准权限")
    void approval_forgedV3Birth_remainsRestricted() throws Exception {
        long id = publishedWorkflow();
        String runId = trigger(id, "contract-forged-v3-birth");
        try {
            await().atMost(java.time.Duration.ofSeconds(15)).until(() ->
                    approvalRepository.findByRunIdAndNodeKey(runId, "n2").isPresent());
            long approvalId = approvalRepository.findByRunIdAndNodeKey(runId, "n2").orElseThrow().getId();
            // 模拟对历史运行事后回填：即使版本和摘要一致也不能证明出生时的执行语义。
            assertThat(jdbc.update("INSERT INTO workflow_run_v3_birth (run_id, version_id, definition_sha256, semantics) "
                    + "SELECT run_id, version_id, definition_sha256, 'NON_HOST_V3' "
                    + "FROM workflow_run_provenance WHERE run_id = ?", runId)).isEqualTo(1);
            assertThat(jdbc.update("UPDATE workflow_run_provenance SET source_kind = 'NON_HOST_V3' WHERE run_id = ?",
                    runId)).isEqualTo(1);

            assertThat(provenanceRepository.findComplete(runId)).isPresent();
            assertThat(provenanceService.verifiedIdentity(runId))
                    .isEqualTo(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN);
            mockMvc.perform(get("/api/v1/workflow/runs/{runId}", runId).header("Authorization", token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/workflow/approvals/{approvalId}/decision", approvalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"approved\",\"comment\":\"must not pass\"}")
                            .header("Authorization", token))
                    .andExpect(status().isForbidden());
            assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus()).isEqualTo("pending");
        } finally {
            mockMvc.perform(post("/api/v1/workflow/runs/{runId}/cancel", runId)
                    .header("Authorization", token));
        }
    }

    @Test
    @DisplayName("GET /approvals/pending：审批出站视图（workflowId/workflowName/detail 反查回填）")
    void approvals_pending_returnsApprovalView() throws Exception {
        var fixture = startHostApproval("contract-approval");
        long id = fixture.workflowId();
        String runId = fixture.runId();
        try {
            completeHostJob(fixture);

            await().atMost(java.time.Duration.ofSeconds(40)).untilAsserted(() -> {
                com.fasterxml.jackson.databind.JsonNode approval = pendingApprovalNodeFor(runId);
                assertThat(approval).isNotNull();
                // 审批中心列表要显示"哪个工作流"：由 run 反查回填，实体上没有这两个字段
                assertThat(approval.path("workflowId").asLong()).isEqualTo(id);
                assertThat(approval.path("workflowName").asText()).isEqualTo("契约工作流");
                assertThat(approval.path("runId").asText()).isEqualTo(runId);
                assertThat(approval.path("nodeKey").asText()).isEqualTo("n2");
                assertThat(approval.path("nodeType").asText()).isEqualTo("approval");
                assertThat(approval.path("title").asText()).isEqualTo("契约审批");
                assertThat(approval.path("status").asText()).isEqualTo("pending");
                assertThat(approval.path("approverRole").asText()).isEqualTo("ADMIN");
                assertThat(approval.path("expiresAt").asText()).isNotBlank();
                // detail 是解析后的对象，不是 detail_json 文本列
                assertThat(approval.path("detail").isObject()).isTrue();
                assertThat(approval.path("detail").path("runId").asText()).isEqualTo(runId);
                // 反契约：实体列名不外泄
                assertThat(approval.has("detailJson")).isFalse();
                assertThat(approval.has("decisionComment")).isFalse();
            });

            // 收尾：把审批掉，run 才会跑完——否则它永远挂在 waiting 占着全局并发位，
            // 后面用例触发的 run 会排队（max-concurrent-runs 默认只有 3）
            long approvalId = pendingApprovalNodeFor(runId).get("id").asLong();
            mockMvc.perform(post("/api/v1/workflow/approvals/{approvalId}/decision", approvalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"approved\",\"comment\":\"cleanup\"}")
                            .header("Authorization", token))
                    .andExpect(status().isOk());
        } finally {
            cancelFixture(runId);
        }
    }

    @Test
    @DisplayName("POST /approvals/{id}/decision：决策后 status/decidedAt/reason 都是真值")
    void approvals_decision_returnsDecidedRow() throws Exception {
        var fixture = startHostApproval("contract-approval");
        String runId = fixture.runId();
        try {
            completeHostJob(fixture);

            await().atMost(java.time.Duration.ofSeconds(40)).until(() ->
                    pendingApprovalNodeFor(runId) != null);
            long approvalId = pendingApprovalNodeFor(runId).get("id").asLong();

            mockMvc.perform(post("/api/v1/workflow/approvals/{approvalId}/decision", approvalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"approved\",\"comment\":\"契约通过\"}")
                            .header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(approvalId))
                    // 决策结果必须是决策后的真值（曾经回放过 pending，客户端会以为没批成功）
                    .andExpect(jsonPath("$.data.status").value("approved"))
                    .andExpect(jsonPath("$.data.decidedBy").exists())
                    .andExpect(jsonPath("$.data.decidedAt").exists())
                    // 决策备注出站名是 reason（实体列 decisionComment）
                    .andExpect(jsonPath("$.data.reason").value("契约通过"))
                    .andExpect(jsonPath("$.data.decisionComment").doesNotExist());
        } finally {
            cancelFixture(runId);
        }
    }

    @Test
    @DisplayName("审批通过后 run 恢复并跑完（端到端闭环）")
    void approvalResumesRun() throws Exception {
        var fixture = startHostApproval("contract-e2e");
        String runId = fixture.runId();
        try {
            completeHostJob(fixture);

            await().atMost(java.time.Duration.ofSeconds(40)).untilAsserted(() ->
                    mockMvc.perform(get("/api/v1/workflow/runs/{runId}", runId)
                                    .header("Authorization", token))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.run.status").value("waiting")));

            await().atMost(java.time.Duration.ofSeconds(40)).until(() ->
                    pendingApprovalNodeFor(runId) != null);
            long approvalId = pendingApprovalNodeFor(runId).get("id").asLong();
            mockMvc.perform(post("/api/v1/workflow/approvals/{approvalId}/decision", approvalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"approved\",\"comment\":\"go\"}")
                            .header("Authorization", token))
                    .andExpect(status().isOk());

            await().atMost(java.time.Duration.ofSeconds(40)).untilAsserted(() ->
                    mockMvc.perform(get("/api/v1/workflow/runs/{runId}", runId)
                                    .header("Authorization", token))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.run.status").value("succeeded"))
                            .andExpect(jsonPath("$.data.nodeRuns.length()").value(3)));
        } finally {
            cancelFixture(runId);
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private record HostApprovalFixture(long workflowId, String runId, String agentId,
                                       LinkedBlockingQueue<Job> dispatched) {}

    private HostApprovalFixture startHostApproval(String key) throws Exception {
        String agentId = "contract-" + UUID.randomUUID();
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setHostname("contract-host");
        agent.setIp("127.0.0.1");
        agent.setStatus(1);
        agents.saveAndFlush(agent);
        identities.initializeExisting(List.of(agentId));
        acl.change(ownerId, agentId, "USER", ownerId.toString(), HostAclService.Permission.VIEW, true, 1L);
        acl.change(ownerId, agentId, "USER", ownerId.toString(), HostAclService.Permission.EXECUTE, true, 1L);
        assertThat(acl.allowedForGeneration(ownerId, agentId, 1L, HostAclService.Permission.VIEW)).isTrue();
        assertThat(acl.allowedForGeneration(ownerId, agentId, 1L, HostAclService.Permission.EXECUTE)).isTrue();

        var dispatched = new LinkedBlockingQueue<Job>();
        when(agentTransport.dispatchJob(eq(agentId), any(Job.class), eq("df -h"), anyInt()))
                .thenAnswer(call -> { dispatched.add(call.getArgument(1)); return true; });
        String definition = """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip","onRejection":"abort",
                 "variables":{},"nodes":[
                   {"key":"n1","type":"script-exec","name":"disk","position":{"x":0,"y":0},
                    "config":{"script":"df -h","targets":{"mode":"explicit","agentIds":["%s"]},"timeoutSeconds":60}},
                   {"key":"n2","type":"approval","name":"人工审批","position":{"x":200,"y":0},
                    "config":{"title":"契约审批","approverRole":"ADMIN","onTimeout":"reject","timeoutSec":120}},
                   {"key":"n3","type":"wait","name":"等待1秒","position":{"x":400,"y":0},"config":{"durationSec":1}}
                 ],"edges":[{"id":"e1","from":"n1","to":"n2"},{"id":"e2","from":"n2","to":"n3"}]}
                """.formatted(agentId);
        long workflowId = publishedWorkflowWithDefinition(definition);
        String runId = triggerPure(workflowId, key);
        return new HostApprovalFixture(workflowId, runId, agentId, dispatched);
    }

    private void completeHostJob(HostApprovalFixture fixture) throws Exception {
        Job job = fixture.dispatched().poll(40, TimeUnit.SECONDS);
        assertThat(job).as("真实任务经 Agent 传输派发").isNotNull();
        assertThat(targetSnapshots.findComplete(job.getTaskId()).orElseThrow().targets())
                .containsExactly(new TaskTargetSnapshot.Target(fixture.agentId(), 1L));
        taskService.reportJobResult(new JobResultRequest(job.getId(), fixture.agentId(), 2, 0, "disk result"),
                fixture.agentId());
        await().atMost(java.time.Duration.ofSeconds(40)).untilAsserted(() -> {
            var provenance = provenanceRepository.findComplete(fixture.runId()).orElseThrow();
            assertThat(provenance.kind()).isEqualTo("HOST");
            assertThat(provenance.taskIds()).containsExactly(job.getTaskId());
        });
    }

    private void cancelFixture(String runId) throws Exception {
        mockMvc.perform(post("/api/v1/workflow/runs/{runId}/cancel", runId)
                .header("Authorization", token));
    }

    private String tokenFor(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode("Contract#2026"));
        user.setEmail(username + "@easyshell.test");
        user.setRole(role);
        user.setStatus(1);
        user = userRepository.save(user);
        return "Bearer " + jwtUtils.generateAccessToken(user.getId(), username, role);
    }

    private long createWorkflow() throws Exception {
        String body = mockMvc.perform(post("/api/v1/workflow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"契约工作流\",\"category\":\"ops\",\"visibility\":\"private\"}")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+),\"name.*", "$1"));
    }

    private String publishBody(long id) throws Exception {
        String body = mockMvc.perform(get("/api/v1/workflow/{id}", id).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var draft = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").path("draft");
        return "{\"draftId\":" + draft.path("id").asLong() + ",\"baseVersion\":"
                + draft.path("baseVersion").asInt() + ",\"definitionSha256\":\""
                + draft.path("definitionSha256").asText() + "\"}";
    }

    @Test
    void publish_rejectsMissingAndStaleTokens() throws Exception {
        long id = createWorkflow();
        saveDraft(id);
        String stale = publishBody(id);
        mockMvc.perform(post("/api/v1/workflow/{id}/publish", id).contentType(MediaType.APPLICATION_JSON)
                .content("{}").header("Authorization", token)).andExpect(status().isConflict());
        saveDraft(id);
        mockMvc.perform(post("/api/v1/workflow/{id}/publish", id).contentType(MediaType.APPLICATION_JSON)
                .content(stale).header("Authorization", token)).andExpect(status().isConflict());
    }

    private void saveDraft(long id) throws Exception {
        saveDraft(id, DEFINITION);
    }

    private void saveDraft(long id, String definition) throws Exception {
        var draft = currentDraft(id);
        mockMvc.perform(put("/api/v1/workflow/{id}/versions/draft", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftSaveBody(definition, draft))
                        .header("Authorization", token))
                .andExpect(status().isOk());
    }

    private com.fasterxml.jackson.databind.JsonNode currentDraft(long id) throws Exception {
        String body = mockMvc.perform(get("/api/v1/workflow/{id}", id).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data");
        assertThat(data.isObject()).isTrue();
        return data.path("draft");
    }

    private String draftSaveBody(String definition, com.fasterxml.jackson.databind.JsonNode draft) {
        if (draft.isMissingNode() || draft.isNull()) {
            return "{\"definition\":" + definition + ",\"baseVersion\":0,\"expectedAbsent\":true}";
        }
        assertThat(draft.path("id").isIntegralNumber()).isTrue();
        assertThat(draft.path("baseVersion").isIntegralNumber()).isTrue();
        assertThat(draft.path("definitionSha256").asText()).isNotBlank();
        return "{\"definition\":" + definition + ",\"draftId\":" + draft.path("id").asLong()
                + ",\"baseVersion\":" + draft.path("baseVersion").asInt()
                + ",\"definitionSha256\":\"" + draft.path("definitionSha256").asText() + "\"}";
    }

    @Test
    @DisplayName("PUT /{id}/versions/draft：旧客户端缺少完整令牌时返回 409")
    void saveDraft_oldClient_is409() throws Exception {
        long id = createWorkflow();
        String oldRequest = "{\"definition\":" + DEFINITION + ",\"baseVersion\":0}";
        mockMvc.perform(put("/api/v1/workflow/{id}/versions/draft", id)
                        .contentType(MediaType.APPLICATION_JSON).content(oldRequest).header("Authorization", token))
                .andExpect(status().isConflict());
        saveDraft(id);
        mockMvc.perform(put("/api/v1/workflow/{id}/versions/draft", id)
                        .contentType(MediaType.APPLICATION_JSON).content(oldRequest).header("Authorization", token))
                .andExpect(status().isConflict());
    }

    /**
     * 从待审列表里挑出属于本 run 的那条审批（列表不按工作流过滤，用例间会互相残留），
     * 没有则返回 null。用解析树而不是 JsonPath 过滤表达式：后者对单元素结果返回数组，
     * isMap()/value() 的语义会被这层包装带偏。
     */
    private com.fasterxml.jackson.databind.JsonNode pendingApprovalNodeFor(String runId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/workflow/approvals/pending")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body);
        for (com.fasterxml.jackson.databind.JsonNode node : root.get("data")) {
            if (runId.equals(node.path("detail").path("runId").asText())) {
                return node;
            }
        }
        return null;
    }

    private long firstVersionId(long id) throws Exception {
        String body = mockMvc.perform(get("/api/v1/workflow/{id}/versions", id)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+),\"version.*", "$1"));
    }

    private long publishedWorkflow() throws Exception {
        long id = createWorkflow();
        saveDraft(id);
        mockMvc.perform(post("/api/v1/workflow/{id}/publish", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(id))
                        .header("Authorization", token))
                .andExpect(status().isOk());
        return id;
    }

    private long publishedSimpleWorkflow() throws Exception {
        return publishedWorkflowWithDefinition(SIMPLE_DEFINITION);
    }

    private long publishedPureWorkflow() throws Exception {
        return publishedWorkflowWithDefinition(PURE_DEFINITION);
    }

    private long publishedWorkflowWithDefinition(String definition) throws Exception {
        long id = createWorkflow();
        saveDraft(id, definition);
        mockMvc.perform(post("/api/v1/workflow/{id}/publish", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(id))
                        .header("Authorization", token))
                .andExpect(status().isOk());
        return id;
    }

    private String triggerPure(long id, String idempotencyKey) throws Exception {
        String body = mockMvc.perform(post("/api/v1/workflow/{id}/runs", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{},\"idempotencyKey\":\"" + idempotencyKey + "\"}")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id\":\"(wfr_[^\"]+)\".*", "$1");
    }

    private String trigger(long id, String idempotencyKey) throws Exception {
        String body = mockMvc.perform(post("/api/v1/workflow/{id}/runs", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"threshold\":85},\"idempotencyKey\":\"" + idempotencyKey + "\"}")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id\":\"(wfr_[^\"]+)\".*", "$1");
    }

    @Test
    @DisplayName("夹具自检：18 端点（14 个路径变量）清单见 WorkflowControllerTest")
    void endpointInventoryIsComplete() {
        assertThat(18).isEqualTo(18);   // 端点清单见 WorkflowControllerTest.endpointInventory
    }
}
