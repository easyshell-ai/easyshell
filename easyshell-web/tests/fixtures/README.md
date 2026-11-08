# tests/fixtures — API stub 数据与端点形状速查表

视觉回归不依赖真实后端：`tests/visual/helpers.ts` 用 `page.route('**/api/**')` 拦掉全部
`/api/**` 请求，优先返回本目录下同名 fixture，缺失时按下面的形状表兜底
（`tests/visual/fixtures.ts` 的 `fallbackFor`）。

## 目录约定

```
tests/fixtures/api/<url.pathname 去掉 /api/v1>.json
```

例：`GET /api/v1/host/provision/unified-list` → `api/host/provision/unified-list.json`；
`GET /api/v1/agents/demo-agent-001/files?path=/` → `api/agents/demo-agent-001/files.json`
（query string 不参与寻址）。

## 响应信封与三种 data 形状

拦截器直接返回 `response.data`（`src/api/request.ts:70`），所以 fixture 必须是完整信封
`{ code, message, data }`。`data` 的形状由端点决定（`src/types/index.ts`）：

| 形状 | 类型 | 消费方式 | 兜底 |
| --- | --- | --- | --- |
| 对象型 | `ApiResponse<T>`，T 为对象 | `res.data.xxx` | `data: {}` |
| 数组型 | `ApiResponse<T[]>` | `res.data \|\| []`、`entries.forEach` | `data: []` |
| 分页型 | `ApiResponse<PageResponse<T>>` | `res.data.content` / `res.data.totalElements` | `data: { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 }` |

**纪律**：① 禁止 `{records,total}` 这类不存在于 `src/types/index.ts` 的键；
② 数组型端点永远给 `data: []`——`src/pages/host/index.tsx:719-722` 的
`const entries = res.data || []; entries.forEach(...)` 收到对象会 TypeError 崩页；
③ 每个路由至少有一个**非空** fixture，否则视觉回归只测了空态。fixture 字段必须逐字段
来自 `src/types/index.ts` 的 VO 定义，不允许凭空造字段。

## 端点形状速查表（与 `tests/visual/fixtures.ts` 的 `SHAPE_BY_PATH` 保持一致）

| 端点（相对 `/api`） | 形状 | 来源 |
| --- | --- | --- |
| `/v1/auth/login`、`/v1/auth/refresh`、`/v1/auth/me` | 对象 | `auth.ts` |
| `/v1/system/version` | 对象 | `system.ts:66` |
| `/v1/system/config` | 数组 | `system.ts:44` |
| `/v1/user/list` | 数组 | `system.ts:12` |
| `/v1/user/{id}` | 对象 | `system.ts:15` |
| `/v1/host/list` | 数组 | `host.ts:4` |
| `/v1/host/{agentId}` | 对象 | `host.ts:8` |
| `/v1/host/dashboard/stats` | 对象 | `host.ts:12` |
| `/v1/host/{agentId}/tags` | 数组 | `host.ts:16` |
| `/v1/task/agent/{agentId}/jobs` | 数组 | `host.ts:20` |
| `/v1/host/{agentId}/metrics` | 数组 | `host.ts:24` |
| `/v1/hosts/{agentId}/(software\|docker/containers\|inventory)` | 数组 | `host.ts:36-45` |
| `/v1/host/provision/unified-list` | 数组 | `provision.ts:61` |
| `/v1/host/provision/list` | 数组 | `provision.ts:8` |
| `/v1/host/provision/{id}` | 对象 | `provision.ts:12` |
| `/v1/agents/{agentId}/files` | 数组 | `pages/terminal/hooks/useFileManager.ts:52` |
| `/v1/task/list` | 数组 | `task.ts:8` |
| `/v1/task/page` | 分页 | `task.ts:12` |
| `/v1/task/{taskId}` | 对象 | `task.ts:16` |
| `/v1/cluster/list` | 数组 | `cluster.ts:4` |
| `/v1/cluster/{id}` | 对象 | `cluster.ts:8` |
| `/v1/script/list`、`/v1/script/templates`、`/v1/script/user-scripts` | 数组 | `script.ts` |
| `/v1/script/{id}` | 对象 | `script.ts` |
| `/v1/script/{id}/parameters` | 数组 | `script.ts` |
| `/v1/tag/list`、`/v1/tag/agent/{agentId}` | 数组 | `tag.ts` |
| `/v1/audit/list` | 分页 | `audit.ts:4` |
| `/v1/ai/config`、`/v1/ai/risk/rules` | 对象 | `ai.ts:29,41` |
| `/v1/ai/chat/sessions`、`/v1/ai/chat/sessions/{id}/messages` | 数组 | `ai.ts:53,61` |
| `/v1/ai/chat/pending-approvals` | 数组 | `ai.ts:97` |
| `/v1/ai/scheduled-tasks`、`/v1/ai/scheduled-tasks/templates` | 数组 | `ai.ts:253,285` |
| `/v1/ai/inspect/reports` | 分页 | `ai.ts:293` |
| `/v1/ai/copilot/models`、`/v1/ai/config/models` | 数组 | `ai.ts:318,322` |
| `/agents`、`/agents/available-tools`、`/agents/available-providers` | 数组 | `agent.ts` |
| `/agents/{id}` | 对象 | `agent.ts` |
| `/memory` | 分页 | `memory.ts:4` |
| `/memory/{id}` | 对象 | `memory.ts:11` |
| `/sop` | 分页 | `sop.ts:5` |
| `/sop/{id}` | 对象 | `sop.ts:12` |

新增端点时：先读 `src/api/*.ts` 的返回类型确定形状，往
`tests/visual/fixtures.ts` 的 `SHAPE_BY_PATH` 补一行（**先具体后泛化**，如
`/v1/host/list` 必须排在 `/v1/host/:id` 之前），再回来更新本表。
