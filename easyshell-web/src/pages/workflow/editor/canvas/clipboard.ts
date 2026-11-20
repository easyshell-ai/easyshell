/**
 * 复制粘贴（选项 3）：Ctrl+C / Ctrl+V，key 自动重生成避免与画布既有节点冲突。
 *
 * 跨画布粘贴 = 同一浏览器内的系统剪贴板：写一份 JSON 到 navigator.clipboard（异步、
 * 可能被权限拒绝），同时留一份模块级兜底缓冲——Playwright / 无权限环境下粘贴仍然可用，
 * 且两条路径都过同一套 parse + 校验，不会把不可信文本塞进 definition。
 */

import { edgeHandleOf, edgeIdFor, nextNodeKey, sourceHandlesForNode } from '../../graph';
import type { WorkflowEdge, WorkflowNode } from '../../types';

export const CLIPBOARD_MIME = 'application/easyshell-node';

export interface ClipboardPayload {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
}

export const PASTE_OFFSET = { x: 40, y: 40 };

let fallbackClipboard: string | null = null;

/** 只收画布内联的边（两端都在复制集合里），跨集合的边不跟过去 */
export function buildClipboardPayload(nodes: WorkflowNode[], edges: WorkflowEdge[]): ClipboardPayload {
  const keys = new Set(nodes.map((n) => n.key));
  return {
    nodes: nodes.map((n) => ({ ...n, config: { ...n.config }, position: { ...n.position } })),
    edges: edges
      .filter((e) => keys.has(e.from) && keys.has(e.to))
      .map((e) => ({ ...e })),
  };
}

export function serializeClipboard(payload: ClipboardPayload): string {
  return JSON.stringify(payload);
}

export function parseClipboard(text: string): ClipboardPayload | null {
  if (!text || !text.trim()) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) return null;
  const candidate = parsed as Partial<ClipboardPayload>;
  if (!Array.isArray(candidate.nodes)) return null;
  const nodes = candidate.nodes.filter(
    (n): n is WorkflowNode =>
      typeof n === 'object' && n !== null && typeof (n as WorkflowNode).key === 'string' && typeof (n as WorkflowNode).type === 'string'
  );
  if (nodes.length === 0) return null;
  const edges = Array.isArray(candidate.edges)
    ? candidate.edges.filter(
        (e): e is WorkflowEdge =>
          typeof e === 'object' && e !== null && typeof (e as WorkflowEdge).from === 'string' && typeof (e as WorkflowEdge).to === 'string'
      )
    : [];
  return { nodes, edges };
}

export interface PasteResult {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
  /** 旧 key -> 新 key，供调用方把选中态切到新节点 */
  keyMap: Record<string, string>;
}

/**
 * 粘贴：key 全部重生成（n1/n2……跳过既有与本次已生成），位置整体偏移避免完全重合。
 */
export function pasteWithNewKeys(
  payload: ClipboardPayload,
  existingKeys: Iterable<string>,
  offset: { x: number; y: number } = PASTE_OFFSET
): PasteResult {
  const used = new Set(existingKeys);
  const keyMap: Record<string, string> = {};

  const nodes = payload.nodes.map((node) => {
    const newKey = nextNodeKey(used);
    used.add(newKey);
    keyMap[node.key] = newKey;
    return {
      ...node,
      key: newKey,
      position: { x: node.position.x + offset.x, y: node.position.y + offset.y },
    };
  });

  const edges = payload.edges
    .filter((e) => keyMap[e.from] && keyMap[e.to])
    .map((e) => {
      const from = keyMap[e.from];
      const to = keyMap[e.to];
      const sourceNode = nodes.find((node) => node.key === from);
      // 显式 handle 必须原样保留（condition true/false、classifier class/default），
      // 只在旧边确实没有 handle 时才按 condition 文本回退。
      const handle = e.sourceHandle
        ? e.sourceHandle
        : edgeHandleOf(nodes, { from, condition: e.condition });
      // 动态 handle（classifier class）必须仍存在于复制后的节点配置里，避免粘贴出不可发布的边
      const valid = !sourceNode || sourceHandlesForNode(sourceNode).includes(handle);
      return valid
        ? ({ ...e, id: edgeIdFor(from, handle, to), from, to } as WorkflowEdge)
        : null;
    })
    .filter((edge): edge is WorkflowEdge => edge !== null);

  return { nodes, edges, keyMap };
}

/** 写剪贴板：系统剪贴板 + 兜底缓冲（两条路都写，读取时任一路可用即可） */
export async function writeClipboard(payload: ClipboardPayload): Promise<void> {
  const text = serializeClipboard(payload);
  fallbackClipboard = text;
  try {
    await navigator.clipboard?.writeText(text);
  } catch {
    // 权限被拒 / 非安全上下文：兜底缓冲已经写好
  }
}

/** 读剪贴板：优先系统剪贴板，失败回落到兜底缓冲 */
export async function readClipboard(): Promise<ClipboardPayload | null> {
  try {
    const text = await navigator.clipboard?.readText();
    const parsed = parseClipboard(text || '');
    if (parsed) return parsed;
  } catch {
    // 权限被拒 / 非安全上下文：走兜底缓冲
  }
  return parseClipboard(fallbackClipboard || '');
}

/** 供测试重置兜底缓冲 */
export function resetClipboardFallback(): void {
  fallbackClipboard = null;
}
