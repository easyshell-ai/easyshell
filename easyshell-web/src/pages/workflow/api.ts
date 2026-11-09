import request from '../../api/request';
import type { ApiResponse, PageResponse } from '../../types';
import type {
  ApprovalStatus,
  ValidationIssue,
  WorkflowDefinition,
  WorkflowDetail,
  WorkflowRun,
  WorkflowRunDetail,
  WorkflowSummary,
  WorkflowTestRun,
  WorkflowValidationResult,
  WorkflowVersionDetail,
  WorkflowVersionSummary,
  WorkflowApproval,
} from './types';

/**
 * 工作流 REST 客户端（设计 §6.1，M1 17 个端点）。
 *
 * 路径/方法/权限严格对齐设计文档：
 *   基础路径 /api/v1/workflow；POST /runs 在 v3 已删除（触发唯一入口是 POST /{id}/runs）。
 * 错误表现按 §6.2：调用方拿到 { code, message } 信封后自行分流（400 表单红字 / 403 页 /
 * 404 页 / 409 三选一弹窗 / 422 issues 面板 / 449 审批确认 / 429 排队 / 500 错误页）。
 *
 * 响应形状以服务端契约为权威（后端 WorkflowApiContractTest 锁死）：详情是
 * {workflow, versions, draft}；草稿/版本详情是平铺的版本摘要 + definition；草稿的乐观锁
 * 令牌叫 baseVersion（不是 version）；validate 返回 {valid, issues, estimatedRisk, riskMatches}；
 * 触发请求体是 {input, idempotencyKey}；run/nodeRun 的 input/output/variables 是解析后的对象。
 */

export interface WorkflowPageQuery {
  page?: number;
  size?: number;
  keyword?: string;
  status?: string;
  category?: string;
}

export interface WorkflowPageResult {
  content: WorkflowSummary[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface WorkflowCreateRequest {
  name: string;
  description?: string;
  category?: string;
  visibility?: 'private' | 'public';
}

export interface WorkflowUpdateRequest {
  name?: string;
  description?: string;
  category?: string;
  visibility?: 'private' | 'public';
}

export interface SaveDraftRequest {
  definition: WorkflowDefinition;
  /** 乐观锁令牌：草稿行的 baseVersion（不是 version 号），不匹配 → 409 VERSION_CONFLICT */
  baseVersion: number;
  changelog?: string;
  /** 409 三选一：reject(默认) / overwrite 覆盖保存 / copy 另存副本 */
  onConflict?: 'reject' | 'overwrite' | 'copy';
}

export interface PublishRequest {
  changelog?: string;
}

/** 触发运行：服务端字段名是 input / idempotencyKey（幂等键在 body，不在请求头） */
export interface TriggerRunRequest {
  input: Record<string, unknown>;
  idempotencyKey: string;
}

export interface ApprovalDecisionRequest {
  decision: 'approved' | 'rejected';
  comment?: string;
}

/* ── 工作流 CRUD ─────────────────────────────────────────────────── */

export function getWorkflowPage(params: WorkflowPageQuery = {}): Promise<ApiResponse<PageResponse<WorkflowSummary>>> {
  return request.get('/v1/workflow/page', { params });
}

export function createWorkflow(data: WorkflowCreateRequest): Promise<ApiResponse<WorkflowSummary>> {
  return request.post('/v1/workflow', data);
}

export function getWorkflowDetail(id: number): Promise<ApiResponse<WorkflowDetail>> {
  return request.get(`/v1/workflow/${id}`);
}

export function updateWorkflow(id: number, data: WorkflowUpdateRequest): Promise<ApiResponse<WorkflowSummary>> {
  return request.put(`/v1/workflow/${id}`, data);
}

/** 软删 → archived（G-18） */
export function archiveWorkflow(id: number): Promise<ApiResponse<null>> {
  return request.delete(`/v1/workflow/${id}`);
}

/* ── 版本 / 草稿 ─────────────────────────────────────────────────── */

export function getWorkflowVersions(id: number): Promise<ApiResponse<WorkflowVersionSummary[]>> {
  return request.get(`/v1/workflow/${id}/versions`);
}

export function getWorkflowVersion(id: number, versionId: number): Promise<ApiResponse<WorkflowVersionDetail>> {
  return request.get(`/v1/workflow/${id}/versions/${versionId}`);
}

export function saveWorkflowDraft(id: number, data: SaveDraftRequest): Promise<ApiResponse<WorkflowVersionDetail>> {
  return request.put(`/v1/workflow/${id}/versions/draft`, data);
}

/* ── 校验 / 发布 / 触发 ──────────────────────────────────────────── */

/**
 * 从响应体里取 issues[]：200 的 validate 是 {valid, issues, …}，422 的 publish 也是同一形状，
 * 而两者的 TS 类型一个是 WorkflowValidationResult、一个是 WorkflowVersionSummary——
 * 按运行时形态取，别让类型把 issues 面板逼成空。
 */
export function issuesOf(data: unknown): ValidationIssue[] {
  const issues = (data as { issues?: unknown } | null | undefined)?.issues;
  return Array.isArray(issues) ? (issues as ValidationIssue[]) : [];
}

export function validateWorkflow(id: number): Promise<ApiResponse<WorkflowValidationResult>> {
  return request.post(`/v1/workflow/${id}/validate`);
}

export function publishWorkflow(id: number, data: PublishRequest = {}): Promise<ApiResponse<WorkflowVersionSummary>> {
  return request.post(`/v1/workflow/${id}/publish`, data);
}

/**
 * 触发运行。幂等键在<b>请求体</b>里（服务端 uk_wfr_idem(started_by, idempotency_key)），
 * 同 userId 同 key 重复触发返回首个 run；同 key 不同入参 → 400 IDEMPOTENCY_CONFLICT。
 */
export function triggerWorkflowRun(id: number, data: TriggerRunRequest): Promise<ApiResponse<WorkflowRun>> {
  return request.post(`/v1/workflow/${id}/runs`, data);
}

/* ── 运行 ────────────────────────────────────────────────────────── */

export function getWorkflowRuns(
  params: { page?: number; size?: number; workflowId?: number; status?: string } = {}
): Promise<ApiResponse<PageResponse<WorkflowRun>>> {
  return request.get('/v1/workflow/runs', { params });
}

export function getWorkflowRun(runId: string): Promise<ApiResponse<WorkflowRunDetail>> {
  return request.get(`/v1/workflow/runs/${runId}`);
}

/** B10：取消是"下一检查点传播"，强披露 */
export function cancelWorkflowRun(runId: string): Promise<ApiResponse<WorkflowRun>> {
  return request.post(`/v1/workflow/runs/${runId}/cancel`);
}

export function testRunNode(runId: string, nodeKey: string): Promise<ApiResponse<WorkflowTestRun>> {
  return request.post(`/v1/workflow/runs/${runId}/nodes/${nodeKey}/test-run`);
}

/* ── 审批 ────────────────────────────────────────────────────────── */

export function getPendingWorkflowApprovals(): Promise<ApiResponse<WorkflowApproval[]>> {
  return request.get('/v1/workflow/approvals/pending');
}

export function decideWorkflowApproval(approvalId: number, data: ApprovalDecisionRequest): Promise<ApiResponse<WorkflowApproval>> {
  return request.post(`/v1/workflow/approvals/${approvalId}/decision`, data);
}

export type {
  ApprovalStatus,
  ValidationIssue,
  WorkflowApproval,
  WorkflowDefinition,
  WorkflowDetail,
  WorkflowRun,
  WorkflowRunDetail,
  WorkflowSummary,
  WorkflowVersionDetail,
  WorkflowVersionSummary,
};
