import { Position } from '@xyflow/react';
import type { Edge, Node } from '@xyflow/react';
import { computeDepthMap, groupByDepth } from '../../utils/dagDepth';
import type {
  WorkflowDefinition,
  WorkflowEdge,
  WorkflowNode,
  WorkflowNodeType,
} from './types';

/* ── 尺寸与把手契约（设计 §4.1） ─────────────────────────────────────── */

export const NODE_WIDTH = 220;
export const NODE_HEIGHT_STANDARD = 72;
export const NODE_HEIGHT_DUAL = 96;

export const NODE_KEY_PATTERN = /^[a-z][a-z0-9_]{0,63}$/;

/** sourceHandle：condition 双出（右上 true / 右下 false），其余单出 */
export const HANDLE_IN = 'in';
export const HANDLE_OUT = 'out';
export const HANDLE_TRUE = 'true';
export const HANDLE_FALSE = 'false';

export interface HandleContract {
  inputs: number;
  outputs: number;
  height: number;
}

export const HANDLE_CONTRACT: Record<WorkflowNodeType, HandleContract> = {
  'script-exec': { inputs: 1, outputs: 1, height: NODE_HEIGHT_STANDARD },
  condition: { inputs: 1, outputs: 2, height: NODE_HEIGHT_DUAL },
  notify: { inputs: 1, outputs: 1, height: NODE_HEIGHT_STANDARD },
  approval: { inputs: 1, outputs: 1, height: NODE_HEIGHT_DUAL },
  wait: { inputs: 1, outputs: 1, height: NODE_HEIGHT_STANDARD },
};

export function nodeHeight(type: WorkflowNodeType): number {
  return HANDLE_CONTRACT[type]?.height ?? NODE_HEIGHT_STANDARD;
}

/** 某类型的全部 source handle id（顺序 = 从上到下） */
export function sourceHandles(type: WorkflowNodeType): string[] {
  return HANDLE_CONTRACT[type]?.outputs === 2 ? [HANDLE_TRUE, HANDLE_FALSE] : [HANDLE_OUT];
}

export function isFalseBranchHandle(sourceHandle: string | null | undefined, sourceType: WorkflowNodeType): boolean {
  return sourceType === 'condition' && sourceHandle === HANDLE_FALSE;
}

/* ── key 生成 ───────────────────────────────────────────────────────── */

/** 面板拖入 / 复制粘贴的 key 自动生成：n1、n2……跳过已占用 */
export function nextNodeKey(existingKeys: Iterable<string>): string {
  const used = new Set(existingKeys);
  let i = 1;
  while (used.has(`n${i}`)) i += 1;
  return `n${i}`;
}

export function isValidNodeKey(key: string): boolean {
  return NODE_KEY_PATTERN.test(key);
}

export function edgeIdFor(from: string, sourceHandle: string | null | undefined, to: string): string {
  return `${from}:${sourceHandle || HANDLE_OUT}->${to}`;
}

/* ── 连线校验（isValidConnection，设计 §4.1） ────────────────────────── */

export type ConnectionRejection =
  | 'ENDPOINT_MISSING'
  | 'SELF_CONNECTION'
  | 'DUPLICATE_EDGE'
  | 'CYCLE';

export interface ConnectionLike {
  source: string;
  target: string;
  sourceHandle?: string | null;
}

export interface ConnectionContext {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
}

/**
 * 客户端成环预检：从 to 出发沿既有边可达 from 即拒绝（服务端权威复检，DagValidator）。
 */
export function wouldCreateCycle(edges: WorkflowEdge[], from: string, to: string): boolean {
  if (from === to) return true;
  const adjacency = new Map<string, string[]>();
  for (const edge of edges) {
    if (!adjacency.has(edge.from)) adjacency.set(edge.from, []);
    adjacency.get(edge.from)!.push(edge.to);
  }
  const stack = [to];
  const seen = new Set<string>();
  while (stack.length > 0) {
    const current = stack.pop()!;
    if (current === from) return true;
    if (seen.has(current)) continue;
    seen.add(current);
    for (const next of adjacency.get(current) || []) stack.push(next);
  }
  return false;
}

/** 返回 null = 允许，否则返回拒绝原因（调用方 message.warning + i18n） */
export function checkConnection(context: ConnectionContext, connection: ConnectionLike): ConnectionRejection | null {
  const { source, target } = connection;
  const sourceNode = context.nodes.find((n) => n.key === source);
  const targetNode = context.nodes.find((n) => n.key === target);
  if (!sourceNode || !targetNode) return 'ENDPOINT_MISSING';
  if (source === target) return 'SELF_CONNECTION';

  const handle = connection.sourceHandle || HANDLE_OUT;
  const duplicate = context.edges.some(
    (e) => e.from === source && e.to === target && edgeHandleOf(context.nodes, e) === handle
  );
  if (duplicate) return 'DUPLICATE_EDGE';
  if (wouldCreateCycle(context.edges, source, target)) return 'CYCLE';
  return null;
}

/**
 * false 分支的边 condition 必须为空（§4.1 ③）——连线时新建的边天然无 condition，
 * 这里约束的是"之后给这条边补 condition"的编辑动作。
 */
export function canSetEdgeCondition(
  nodes: WorkflowNode[],
  edge: { from: string; sourceHandle?: string | null }
): boolean {
  const sourceType = nodes.find((n) => n.key === edge.from)?.type;
  if (!sourceType) return false;
  return !isFalseBranchHandle(edge.sourceHandle, sourceType);
}

/* ── definition <-> React Flow 互转 ─────────────────────────────────── */

export interface WorkflowNodeData {
  key: string;
  nodeType: WorkflowNodeType;
  name: string;
  /** 运行态着色（编辑器态为 undefined） */
  status?: string;
  outputVar?: string;
  [extra: string]: unknown;
}

export type WorkflowFlowNode = Node<WorkflowNodeData, 'workflowNode'>;
export type WorkflowFlowEdge = Edge<{ condition?: string; onUpstreamFailure?: string }, 'workflowEdge'>;

export function definitionToFlow(definition: WorkflowDefinition): {
  nodes: WorkflowFlowNode[];
  edges: WorkflowFlowEdge[];
} {
  const nodes: WorkflowFlowNode[] = (definition.nodes || []).map((node) => ({
    id: node.key,
    type: 'workflowNode',
    position: { x: node.position?.x ?? 0, y: node.position?.y ?? 0 },
    data: {
      key: node.key,
      nodeType: node.type,
      name: node.name,
      status: undefined,
      outputVar: node.outputVar,
    },
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    deletable: true,
  }));

  const edges: WorkflowFlowEdge[] = (definition.edges || []).map((edge) => {
    const sourceHandle = edgeHandleOf(definition.nodes || [], edge);
    return {
      id: edge.id || edgeIdFor(edge.from, sourceHandle, edge.to),
      source: edge.from,
      target: edge.to,
      sourceHandle,
      type: 'workflowEdge',
      data: { condition: edge.condition, onUpstreamFailure: edge.onUpstreamFailure },
      label: edge.condition || undefined,
    };
  });

  return { nodes, edges };
}

/** condition 节点：带 condition 的边走 true handle，不带的走 false handle */
export function edgeHandleOf(nodes: WorkflowNode[], edge: { from: string; condition?: string }): string {
  const source = nodes.find((n) => n.key === edge.from);
  if (source?.type !== 'condition') return HANDLE_OUT;
  return edge.condition ? HANDLE_TRUE : HANDLE_FALSE;
}

export function flowToDefinition(
  nodes: WorkflowFlowNode[],
  edges: WorkflowFlowEdge[],
  base: WorkflowDefinition
): WorkflowDefinition {
  const positionById = new Map(nodes.map((n) => [n.id, n.position]));
  const order: string[] = nodes.map((n) => n.id);

  const definitionNodes: WorkflowNode[] = order
    .map((key) => {
      const existing = base.nodes.find((n) => n.key === key);
      const position = positionById.get(key)!;
      return {
        ...(existing || { key, type: 'script-exec', name: key, config: {} }),
        key,
        position: { x: Math.round(position.x), y: Math.round(position.y) },
      } as WorkflowNode;
    })
    .filter((n) => isValidNodeKey(n.key));

  const definitionEdges: WorkflowEdge[] = edges
    .filter((e) => definitionNodes.some((n) => n.key === e.source) && definitionNodes.some((n) => n.key === e.target))
    .map((e) => {
      const sourceType = definitionNodes.find((n) => n.key === e.source)!.type;
      const isFalse = isFalseBranchHandle(e.sourceHandle, sourceType);
      const condition = isFalse ? undefined : e.data?.condition || undefined;
      const existing = base.edges.find((edge) => edge.id === e.id);
      return {
        id: e.id || edgeIdFor(e.source, e.sourceHandle, e.target),
        from: e.source,
        to: e.target,
        ...(condition ? { condition } : {}),
        ...(e.data?.onUpstreamFailure ? { onUpstreamFailure: e.data.onUpstreamFailure as 'abort' | 'skip' | 'partial' } : {}),
        ...(existing?.onUpstreamFailure && !e.data?.onUpstreamFailure
          ? { onUpstreamFailure: existing.onUpstreamFailure }
          : {}),
      } as WorkflowEdge;
    });

  return {
    schemaVersion: 1,
    defaultOnUpstreamFailure: base.defaultOnUpstreamFailure || 'skip',
    onRejection: base.onRejection || 'abort',
    variables: base.variables || {},
    nodes: definitionNodes,
    edges: definitionEdges,
  };
}

/* ── 自动布局（分层，不引 elkjs） ────────────────────────────────────── */

export interface LayoutOptions {
  xGap?: number;
  yGap?: number;
  originX?: number;
  originY?: number;
}

export function autoLayoutPositions(
  nodes: WorkflowNode[],
  edges: WorkflowEdge[],
  options: LayoutOptions = {}
): Record<string, { x: number; y: number }> {
  const { xGap = NODE_WIDTH + 80, yGap = 120, originX = 80, originY = 80 } = options;
  const keys = nodes.map((n) => n.key);
  const depths = computeDepthMap(
    keys,
    edges.map((e) => ({ from: e.from, to: e.to }))
  );
  const layers = groupByDepth(keys, depths);

  const positions: Record<string, { x: number; y: number }> = {};
  for (const [depth, layerKeys] of layers) {
    layerKeys.forEach((key, indexInLayer) => {
      positions[key] = { x: originX + depth * xGap, y: originY + indexInLayer * yGap };
    });
  }
  return positions;
}

/* ── 删除影响面（删除确认弹窗用） ────────────────────────────────────── */
export interface DeleteImpact {
  nodes: string[];
  edges: string[];
}

/** 选中节点 + 仅与选中节点相连的边（边随节点一起删） */
export function computeDeleteImpact(selectedNodeKeys: string[], edges: WorkflowEdge[]): DeleteImpact {
  const nodeSet = new Set(selectedNodeKeys);
  return {
    nodes: [...nodeSet],
    edges: edges.filter((e) => nodeSet.has(e.from) || nodeSet.has(e.to)).map((e) => e.id),
  };
}

/* ── 新建节点默认值 ────────────────────────────────────────────────── */

export const DEFAULT_NODE_CONFIG: Record<WorkflowNodeType, Record<string, unknown>> = {
  'script-exec': { scriptContent: '', timeoutSec: 600 },
  condition: { expression: '' },
  notify: { channel: 'webhook', message: '' },
  approval: { mode: 'confirm', title: '' },
  wait: { durationSec: 60 },
};

export function createWorkflowNode(
  type: WorkflowNodeType,
  key: string,
  position: { x: number; y: number },
  name?: string
): WorkflowNode {
  return {
    key,
    type,
    name: name || key,
    position,
    config: { ...DEFAULT_NODE_CONFIG[type] },
    retryMax: 0,
    retryIntervalSec: 5,
    timeoutSec: type === 'wait' ? null : 600,
    onFailure: 'abort',
  };
}
