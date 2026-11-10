/**
 * definition_json 的 TS 镜像（服务端权威 schema = workflow/model/*.java，未知字段一律拒绝，
 * 设计 §4.1 / NF-4：schema 冻结为 definition.schema.json 进 Git）。
 *
 * 字段名与服务端 WorkflowDefinition / WorkflowNode / WorkflowEdge / VariableSpec 一一对应，
 * 前端镜像仅供编辑体验；权威校验在服务端。
 */

export type WorkflowNodeType =
  | 'start'
  | 'script-exec'
  | 'transform'
  | 'condition'
  | 'ai-analyze'
  | 'llm'
  | 'classifier'
  | 'code'
  | 'approval'
  | 'notify'
  | 'wait'
  | 'end';

export type VariableValueType = 'string' | 'number' | 'boolean' | 'json';

export interface VariableSpec {
  type: VariableValueType;
  /** 展示名；触发弹窗优先显示 label，缺省显示 name */
  label?: string;
  required?: boolean;
  default?: unknown;
  description?: string;
  /** 仅 type=string：下拉枚举值；非空时触发弹窗渲染 Select */
  options?: string[];
}

/** abort / skip / partial / compensate(M2) */
export type FailurePolicyCode = 'abort' | 'skip' | 'partial' | 'compensate';

/**
 * 节点私有 config 的字段名口径（服务端权威，逐字对齐执行器的读取键）：
 * script-exec → `ScriptExecNodeExecutor.configString("script")` /
 *   `configMap("targets")` / `config.get("parameters")` / `config.get("rawParams")`
 * notify       → `NotifyNodeExecutor.configList("channels")` / `configString("messageTemplate")`
 * approval     → `ApprovalNodeExecutor.configString("title"|"mode"|"approverRole"|"onTimeout")`
 * condition    → `ConditionNodeExecutor.configString("expression")`
 * wait         → `WaitNodeExecutor.configInt("durationSec")`
 */

export type TargetsMode = 'explicit' | 'cluster' | 'tag' | 'variable';

export interface TargetsConfig {
  mode: TargetsMode;
  /** explicit 模式：agent id 列表 */
  agentIds?: string[];
  /** cluster 模式：集群 id 列表 */
  clusterIds?: number[];
  /** tag 模式：标签 id 列表 */
  tagIds?: number[];
  /** variable 模式：上游产出主机列表的表达式路径 */
  variableRef?: string;
}

export interface ScriptExecConfig {
  /** 脚本文本（未渲染，含 {{NAME}} 槽位）；与 scriptId 二选一 */
  script?: string;
  /** 可选：脚本库引用（1.3.0 语义，当前服务端不读，仅随定义保存） */
  scriptId?: string;
  /** 槽位取值：字面量或 ${path} 表达式 */
  parameters?: Record<string, string>;
  /** 声明为 `{{NAME:raw}}` 的参数名（必须同时 riskPolicy >= allow_medium） */
  rawParams?: string[];
  /** 寻址（必填，mode 缺失 → 发布 ERROR） */
  targets?: TargetsConfig;
  /** reject(默认) / raw / truncate */
  onUnsafeValue?: 'reject' | 'raw' | 'truncate';
  /** block(默认) / allow_medium / strict */
  riskPolicy?: 'block' | 'allow_medium' | 'strict';
  /** task 派发超时秒（节点级 timeoutSec 之外的第二口径，见执行器 :328） */
  timeoutSeconds?: number;
}

export interface NotifyConfig {
  /** 渠道 key 数组（1..6）：telegram / discord / dingtalk / slack / feishu / wechat-work */
  channels?: string[];
  /** 消息模板：支持 ${path} 与 {{NAME}}（NAME 取自 parameters） */
  messageTemplate?: string;
  /** {{NAME}} 槽位取值 */
  parameters?: Record<string, string>;
  /** info(默认) / warning / error，非 info 时给内容加前缀 */
  level?: 'info' | 'warning' | 'error';
}

export type ApprovalMode = 'approval' | 'human-confirm';

export interface ApprovalConfig {
  /** approval(默认) / human-confirm（后者标题加"人工确认"前缀） */
  mode?: ApprovalMode;
  /** 审批标题（必填，支持模板渲染） */
  title?: string;
  /** 详情模板；缺省用执行器内置快照 */
  detailTemplate?: string;
  /** ADMIN(默认) / SUPER_ADMIN */
  approverRole?: 'ADMIN' | 'SUPER_ADMIN';
  /** 60..604800 */
  timeoutSec?: number;
  /** approve / reject(默认) / fail */
  onTimeout?: 'approve' | 'reject' | 'fail';
  /** 标题/详情模板里的 {{NAME}} 槽位取值 */
  parameters?: Record<string, string>;
}

export interface WaitConfig {
  /** 1..3600 */
  durationSec?: number;
}

export interface ConditionConfig {
  /** 条件表达式（≤512 字符，白名单子集） */
  expression?: string;
}

/**
 * start 的入参不在 config 里：工作流入参统一写 definition.variables（§2.1）。
 * 保留空接口是为了让 NodeFormPanel / inspector 的按类型分派有一致的类型出口。
 */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface StartConfig {}

export interface TransformMapping {
  name: string;
  /** 字面量或 ${path}；纯 ${path} 保留原类型 */
  value: string;
}

export interface TransformConfig {
  mappings?: TransformMapping[];
}

export type AiOutputMode = 'text' | 'json';

export interface AiAnalyzeConfig {
  instructions?: string;
  /** 上游输入表达式，如 ${nodes.check.output}；可截断到 maxInputChars */
  input?: string;
  /** 8000 默认；AI 只做分析，不执行命令 */
  maxInputChars?: number;
  /** text(默认) / json：json 模式要求模型返回结构化 JSON */
  outputMode?: AiOutputMode;
  /** json 模式的 schema 提示（仅作为提示词指导，不参与本地 schema 校验） */
  jsonSchema?: string;
}

/** LLM 节点：通用文本 / 结构化 JSON 生成，不执行任何命令 */
export interface LlmConfig {
  /** null = 默认 AI provider */
  provider?: string | null;
  /** null = provider 默认模型 */
  model?: string | null;
  systemPrompt?: string;
  /** 必填模板，支持 ${path} */
  userPrompt?: string;
  /** text(默认) / json */
  outputMode?: AiOutputMode;
  /** json 模式下的 schema 提示 */
  jsonSchema?: string;
  /** 0..2 */
  temperature?: number;
  /** 1000..32000 */
  maxInputChars?: number;
}

export interface ClassifierClass {
  name: string;
  description?: string;
}

/** 问题分类节点：每个 class 一个出边把手 + default */
export interface ClassifierConfig {
  input?: string;
  classes?: ClassifierClass[];
  instructions?: string;
  provider?: string | null;
  model?: string | null;
}

export type CodeLanguage = 'javascript' | 'python';

/** 沙箱数据转换节点：只做数据变换，不做主机操作 */
export interface CodeConfig {
  language?: CodeLanguage;
  code?: string;
  inputs?: Record<string, string>;
  /** 1..15 */
  timeoutSec?: number;
}

export interface EndOutput {
  name: string;
  /** 字面量或 ${path}；纯 ${path} 保留原类型 */
  value: string;
}

export interface EndConfig {
  outputs?: EndOutput[];
}

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
  /**
   * 出边语义（§2.2）：condition 节点写 `true` / `false`；其他节点写 `out`（可省略）。
   * 旧定义没有该字段时，condition 节点回落到 condition 表达式映射。
   */
  sourceHandle?: string;
  /** 旧语义门控；condition 的 true/false handle 优先于它 */
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
