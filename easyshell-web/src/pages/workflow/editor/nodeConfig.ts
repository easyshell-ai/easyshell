/**
 * 节点配置表单的**纯数据层**：常量、类型与 id 列表解析。
 *
 * 与 `NodeConfigFields.tsx`（组件）分开，是因为 eslint 的 react-refresh 规则要求
 * 一个文件只导出组件；把常量混在 .tsx 里会直接报错。
 *
 * 字段名以服务端执行器为权威（`ScriptExecNodeExecutor.configString("script")` /
 * `configMap("targets")`、`NotifyNodeExecutor.configList("channels")`），
 * 这里不做任何翻译层——翻译层正是契约错位的温床。
 */

/** notify 渠道：ChannelMessageRouter.serviceMap 的 6 个真实 key */
export const NOTIFY_CHANNELS = ['telegram', 'discord', 'dingtalk', 'slack', 'feishu', 'wechat-work'];

/** targets.mode：ScriptExecNodeExecutor.resolveTargets 的四个分支 */
export const TARGETS_MODES = ['explicit', 'cluster', 'tag', 'variable'] as const;

export type TargetsMode = (typeof TARGETS_MODES)[number];

export interface TargetsValue {
  mode: TargetsMode;
  /** explicit：agent id 列表（agent 协议里的字符串 id） */
  agentIds: string[];
  /** cluster：集群 id 列表（数字） */
  clusterIds: number[];
  /** tag：标签 id 列表（数字） */
  tagIds: number[];
  /** variable：上游产出主机列表的表达式路径，如 nodes.pick.output.hosts */
  variableRef: string;
}

export const EMPTY_TARGETS: TargetsValue = {
  mode: 'explicit',
  agentIds: [],
  clusterIds: [],
  tagIds: [],
  variableRef: '',
};

/** 逗号 / 分号 / 空白分隔的 id 列表 → 去空去重（顺序保持首次出现） */
export function parseIdList(raw: string): string[] {
  const seen = new Set<string>();
  for (const piece of raw.split(/[,;\s]+/)) {
    const trimmed = piece.trim();
    if (trimmed) seen.add(trimmed);
  }
  return [...seen];
}

/** id 列表 → 输入框原文（与 parseIdList 互逆） */
export function joinIdList(ids: string[]): string {
  return (ids || []).join(', ');
}

/** 逗号分隔的数字 id → number[]（非数字项直接丢弃，由服务端校验兜底） */
export function parseNumberList(raw: string): number[] {
  const result: number[] = [];
  for (const piece of raw.split(/[,;\s]+/)) {
    const trimmed = piece.trim();
    if (!trimmed) continue;
    const value = Number(trimmed);
    if (Number.isFinite(value)) result.push(value);
  }
  return result;
}

export function joinNumberList(ids: number[]): string {
  return (ids || []).join(', ');
}
