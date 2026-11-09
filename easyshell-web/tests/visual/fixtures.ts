/**
 * 兜底 stub：按端点真实响应形状分流（设计文档 §6.5 / C-4 闭环）。
 *
 * 先读真实契约（src/api/*.ts 的返回类型 + src/types/index.ts）：
 * - 对象型 ApiResponse<T>（T 是对象，如 getHostDetail → Agent）        → data: {}
 * - 数组型 ApiResponse<T[]>（如 getUnifiedHostList → HostCredentialVO[]）→ data: []
 * - 分页型 ApiResponse<PageResponse<T>>（如 getTaskPage、getAuditLogList）→ data: { content: [], totalElements: 0, ... }
 *
 * 三条纪律（§6.5）：① 禁止出现 {records,total} 这类不存在于 src/types/index.ts 的键；
 * ② 数组型端点永远给 data: []（空数组也是数组，`entries.forEach` 安全——host/index.tsx:719-722
 * 收到对象会直接 TypeError 崩页）；③ 每个路由至少有一个非空 fixture（tests/fixtures/api/**），
 * 否则视觉回归只测了空态；端点形状速查表维护在 tests/fixtures/README.md。
 */

export type StubShape = 'object' | 'array' | 'page';

/** 有序表：先具体后泛化（/v1/host/list 必须排在 /v1/host/:id 之前） */
const SHAPE_BY_PATH: Array<[RegExp, StubShape]> = [
  // ── 数组型 ApiResponse<T[]> ──────────────────────────────────────────────
  [/\/v1\/host\/provision\/unified-list$/, 'array'], // getUnifiedHostList：崩页高发，必须是数组
  [/\/v1\/host\/provision\/list$/, 'array'],
  [/\/v1\/host\/list$/, 'array'],
  [/\/v1\/host\/[^/]+\/tags$/, 'array'], // getAgentTags
  [/\/v1\/task\/agent\/[^/]+\/jobs$/, 'array'], // getAgentJobs
  [/\/v1\/host\/[^/]+\/metrics$/, 'array'], // getAgentMetrics
  [/\/v1\/hosts\/[^/]+\/(software|docker\/containers|inventory)$/, 'array'],
  [/\/v1\/agents\/[^/]+\/files$/, 'array'], // useFileManager：FileInfo[]
  [/\/v1\/task\/list$/, 'array'],
  [/\/v1\/cluster\/list$/, 'array'],
  [/\/v1\/script\/(list|templates|user-scripts)$/, 'array'],
  [/\/v1\/script\/[^/]+\/parameters$/, 'array'],
  [/\/v1\/tag\/list$/, 'array'],
  [/\/v1\/tag\/agent\/[^/]+$/, 'array'],
  [/\/v1\/user\/list$/, 'array'],
  [/\/v1\/system\/config$/, 'array'], // getSystemConfigList
  [/\/v1\/ai\/chat\/sessions$/, 'array'],
  [/\/v1\/ai\/chat\/sessions\/[^/]+\/messages$/, 'array'],
  [/\/v1\/ai\/chat\/pending-approvals$/, 'array'],
  [/\/v1\/ai\/scheduled-tasks$/, 'array'],
  [/\/v1\/ai\/scheduled-tasks\/templates$/, 'array'],
  [/\/v1\/ai\/copilot\/models$/, 'array'],
  [/\/v1\/ai\/config\/models$/, 'array'],
  [/^\/api\/agents\/available-(tools|providers)$/, 'array'],
  [/^\/api\/agents$/, 'array'], // listAgents（GET /api/agents）
  // ── 分页型 ApiResponse<PageResponse<T>> ─────────────────────────────────
  [/\/v1\/audit\/list$/, 'page'], // getAuditLogList
  [/\/v1\/task\/page$/, 'page'], // getTaskPage
  [/\/v1\/workflow\/page$/, 'page'], // getWorkflowPage
  [/\/v1\/workflow\/runs$/, 'page'], // getWorkflowRuns
  [/\/v1\/workflow\/approvals\/pending$/, 'array'], // getPendingWorkflowApprovals
  [/\/v1\/ai\/inspect\/reports$/, 'page'], // getInspectReports
  [/^\/api\/memory$/, 'page'], // getMemoryList
  [/^\/api\/sop$/, 'page'], // getSopList
  // ── 对象型 ApiResponse<T> ───────────────────────────────────────────────
  [/\/v1\/host\/dashboard\/stats$/, 'object'], // getDashboardStats
  [/^\/api\/v1\/auth\/(login|refresh|me)$/, 'object'],
  [/^\/api\/v1\/ai\/config$/, 'object'], // getAiConfig
  [/^\/api\/v1\/ai\/risk\/rules$/, 'object'],
  [/\/v1\/system\/version$/, 'object'],
  [/\/v1\/host\/provision\/[^/]+$/, 'object'],
  [/\/v1\/host\/[^/]+$/, 'object'], // getHostDetail
  [/\/v1\/cluster\/[^/]+$/, 'object'], // getClusterDetail
  [/\/v1\/task\/[^/]+$/, 'object'], // getTaskDetail
  [/\/v1\/script\/[^/]+$/, 'object'], // getScript
  [/\/v1\/workflow\/runs\/[^/]+$/, 'object'], // getWorkflowRun
  [/\/v1\/workflow\/[^/]+\/versions\/[^/]+$/, 'object'], // getWorkflowVersion / getWorkflowDraft
  [/\/v1\/workflow\/[^/]+\/versions$/, 'array'], // getWorkflowVersions
  [/\/v1\/workflow\/[^/]+$/, 'object'], // getWorkflowDetail
  [/\/v1\/user\/[^/]+$/, 'object'], // getUserDetail
  [/^\/api\/agents\/[^/]+$/, 'object'], // getAgent（toggle 也走这里）
  [/^\/api\/memory\/[^/]+$/, 'object'], // getMemory
  [/^\/api\/sop\/[^/]+$/, 'object'], // getSop
];

const EMPTY_PAGE = { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 };

/**
 * 未命中路由表的端点给 data: null——对象型消费方普遍走 `res.data?.xxx` / `res.data || {}`，
 * 数组型走 `res.data || []`；真出现崩页说明漏了形状，往 SHAPE_BY_PATH 补一行即可。
 */
export function fallbackFor(pathname: string): Record<string, unknown> {
  const shape = SHAPE_BY_PATH.find(([re]) => re.test(pathname))?.[1];
  const ok = { code: 200, message: 'ok' };
  if (shape === 'array') return { ...ok, data: [] };
  if (shape === 'page') return { ...ok, data: { ...EMPTY_PAGE } };
  if (shape === 'object') return { ...ok, data: {} };
  return { ...ok, data: null };
}
