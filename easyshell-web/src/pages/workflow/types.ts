/**
 * definition_json 的 TS 镜像（服务端权威 schema = workflow/model/*.java，未知字段一律拒绝，
 * 设计 §4.1 / NF-4：schema 冻结为 definition.schema.json 进 Git）。
 *
 * 字段名与服务端 WorkflowDefinition / WorkflowNode / WorkflowEdge / VariableSpec 一一对应，
 * 前端镜像仅供编辑体验；权威校验在服务端。
 */

export type WorkflowNodeType = 'script-exec' | 'condition' | 'notify' | 'approval' | 'wait';

export type VariableValueType = 'string' | 'number' | 'boolean' | 'json';

export interface VariableSpec {
  type: VariableValueType;
  required?: boolean;
  default?: unknown;
  description?: string;
}

/** abort / skip / partial / compensate(M2) */
export type FailurePolicyCode = 'abort' | 'skip' | 'partial' | 'compensate';

export interface WorkflowNode {
  /** ^[a-z][a-z0-9_]{0,63}$，图内稳定 ID */
  key: string;
  type: WorkflowNodeType;
  name: string;
  position: { x: number; y: number };
  /** 节点类型私有配置 */
  config: Record<string, unknown>;
  retryMax?: number;
  retryIntervalSec?: number;
  /** null = 用 engine.default-node-timeout-sec */
  timeoutSec?: number | null;
  onFailure?: FailurePolicyCode;
  outputVar?: string;
  /** reject(默认) / raw / truncate */
  onUnsafeValue?: 'reject' | 'raw' | 'truncate';
  /** block(默认) / allow_medium / strict */
  riskPolicy?: 'block' | 'allow_medium' | 'strict';
}

export interface WorkflowEdge {
  id: string;
  from: string;
  to: string;
  /** 缺省 = 恒真（唯一门控）；condition 的 false handle 连出的边必须为空 */
  condition?: string;
  onUpstreamFailure?: 'abort' | 'skip' | 'partial';
}

export interface WorkflowDefinition {
  schemaVersion: 1;
  defaultOnUpstreamFailure: 'abort' | 'skip' | 'partial';
  onRejection: 'abort' | 'continue';
  variables: Record<string, VariableSpec>;
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
}

/* ── API 镜像（§6.1 / §6.2） ─────────────────────────────────────────── */

import type { PageResponse } from '../../types';

export type { PageResponse };

export type WorkflowStatus = 'draft' | 'published' | 'archived';
export type WorkflowVisibility = 'private' | 'public';

export interface WorkflowSummary {
  id: number;
  name: string;
  description?: string | null;
  category?: string | null;
  status: WorkflowStatus;
  visibility: WorkflowVisibility;
  currentVersionId?: number | null;
  currentVersion?: number | null;
  runCount: number;
  successCount: number;
  lastRunAt?: string | null;
  createdBy: number;
  createdAt: string;
  updatedAt?: string | null;
}

export interface WorkflowVersionSummary {
  id: number;
  version: number;
  baseVersion: number;
  changelog?: string | null;
  isValid: boolean;
  estimatedRisk?: string | null;
  publishedAt?: string | null;
  createdAt: string;
}

export interface WorkflowVersionDetail extends WorkflowVersionSummary {
  definition: WorkflowDefinition;
}

export interface WorkflowDetail {
  workflow: WorkflowSummary;
  versions: WorkflowVersionSummary[];
  /** 草稿行；不存在时为 null（发布后会自动新建，G-14） */
  draft: WorkflowVersionDetail | null;
}

export interface ValidationIssue {
  severity: 'ERROR' | 'WARNING';
  code: string;
  message: string;
  nodeKey?: string | null;
}

/**
 * 校验结果（POST /{id}/validate 与发布 422 的响应体）。
 * 不是裸 issues[]：estimatedRisk 是插值前估算值（G-16：UI 必须标注"估算值"）。
 */
export interface WorkflowValidationResult {
  valid: boolean;
  issues: ValidationIssue[];
  estimatedRisk?: string | null;
  riskMatches?: string[] | null;
}

export type RunStatus =
  | 'pending'
  | 'queued'
  | 'running'
  | 'waiting'
  | 'succeeded'
  | 'failed'
  | 'cancelled'
  | 'timeout'
  | 'interrupted';

export type NodeRunStatus =
  | 'pending'
  | 'running'
  | 'waiting'
  | 'succeeded'
  | 'failed'
  | 'skipped'
  | 'cancelled'
  | 'timeout';

export interface WorkflowRun {
  id: string;
  workflowId: number;
  versionId: number;
  versionNo: number;
  triggerType: 'manual' | 'scheduled' | 'replay';
  status: RunStatus;
  input: Record<string, unknown>;
  output?: Record<string, unknown> | null;
  errorMessage?: string | null;
  variables?: Record<string, unknown> | null;
  variablesTruncated?: boolean;
  currentNodes?: string[] | null;
  cancelRequested?: boolean;
  startedBy: number;
  createdAt: string;
  updatedAt?: string | null;
  finishedAt?: string | null;
}

export interface WorkflowNodeRun {
  id: string;
  runId: string;
  nodeKey: string;
  nodeType: WorkflowNodeType;
  attempt: number;
  status: NodeRunStatus;
  taskId?: string | null;
  input?: Record<string, unknown> | null;
  output?: Record<string, unknown> | null;
  errorMessage?: string | null;
  warningMessage?: string | null;
  createdAt: string;
  finishedAt?: string | null;
}

export interface WorkflowRunDetail {
  run: WorkflowRun;
  nodeRuns: WorkflowNodeRun[];
}

/** 单节点试运行结果（POST /runs/{runId}/nodes/{nodeKey}/test-run） */
export interface WorkflowTestRun {
  nodeKey: string;
  nodeType: WorkflowNodeType | string;
  /** ok / blocked */
  status: string;
  riskLevel?: string | null;
  /** 只存模式串，禁止存命令原文（§6.2 / B2 #13） */
  riskMatches?: string[] | null;
  riskPolicy?: string | null;
  /** 渲染后的脚本全文（L2 之后）；condition 节点为 null */
  renderedScript?: string | null;
  resolvedParameters?: Record<string, string> | null;
  conditionResult?: boolean | null;
  targetAgentCount?: number | null;
  warnings?: string[] | null;
}

export type ApprovalStatus = 'pending' | 'approved' | 'rejected' | 'timeout' | 'cancelled';

export interface WorkflowApproval {
  id: number;
  runId: string;
  workflowId?: number | null;
  workflowName?: string | null;
  nodeKey: string;
  nodeType: string;
  title: string;
  detail?: Record<string, unknown> | null;
  riskLevel?: string | null;
  /** 只存模式串，禁止存命令原文（§6.2 / B2 #13） */
  riskMatches?: string | null;
  status: ApprovalStatus;
  approverRole: string;
  decidedBy?: number | null;
  decidedAt?: string | null;
  reason?: string | null;
  expiresAt?: string | null;
  createdAt: string;
}
