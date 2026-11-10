import { Position } from '@xyflow/react';
import type { Edge, Node } from '@xyflow/react';
import { computeDepthMap, groupByDepth } from '../../utils/dagDepth';
import type {
  VariableSpec,
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

/** sourceHandle：condition 双出（右上 true / 右下 false），classifier 每个 class + default */
export const HANDLE_IN = 'in';
export const HANDLE_OUT = 'out';
export const HANDLE_TRUE = 'true';
export const HANDLE_FALSE = 'false';
export const HANDLE_CLASSIFIER_DEFAULT = 'default';

export const CLASSIFIER_NAME_PATTERN = /^[a-z][a-z0-9_]{0,63}$/;
export const MAX_CLASSIFIER_CLASSES = 10;

export interface HandleContract {
  inputs: number;
  outputs: number;
  height: number;
}

export const HANDLE_CONTRACT: Record<WorkflowNodeType, HandleContract> = {
  start: { inputs: 0, outputs: 1, height: NODE_HEIGHT_STANDARD },
  'script-exec': { inputs: 1, outputs: 1, height: NODE_HEIGHT_STANDARD },
  transform: { inputs: 1, outputs: 1, height: NODE_HEIGHT_STANDARD },
  condition: { inputs: 1, outputs: 2, height: NODE_HEIGHT_DUAL },
  'ai-analyze': { inputs: 1, outputs: 1, height: NODE_HEIGHT_STANDARD },
  llm: { inputs: 1, outputs: 1, height: NODE_HEIGHT_STANDARD },
  classifier: { inputs: 1, outputs: 2, height: NODE_HEIGHT_DUAL },
  code: { inputs: 1, outputs: 1, height: NODE_HEIGHT_STANDARD },
  approval: { inputs: 1, outputs: 1, height: NODE_HEIGHT_DUAL },
  notify: { inputs: 1, outputs: 1, height: NODE_HEIGHT_STANDARD },
  wait: { inputs: 1, outputs: 1, height: NODE_HEIGHT_STANDARD },
  end: { inputs: 1, outputs: 0, height: NODE_HEIGHT_STANDARD },
};

export function nodeHeight(type: WorkflowNodeType, sourceHandleCount?: number): number {
  if (type === 'classifier' && sourceHandleCount && sourceHandleCount > 3) {
    // 每个 class 一个带标签的把手：3 个以内沿用双出口高度，更多时按行增高
    return Math.max(NODE_HEIGHT_DUAL, 28 + sourceHandleCount * 18);
  }
  return HANDLE_CONTRACT[type]?.height ?? NODE_HEIGHT_STANDARD;
}

export function nodeHasInput(type: WorkflowNodeType): boolean {
  return (HANDLE_CONTRACT[type]?.inputs ?? 0) > 0;
}

export function nodeHasOutput(type: WorkflowNodeType): boolean {
  return (HANDLE_CONTRACT[type]?.outputs ?? 0) > 0;
}

/** 某类型的全部 source handle id（顺序 = 从上到下）；end 节点返回空数组 */
export function sourceHandles(type: WorkflowNodeType): string[] {
  const outputs = HANDLE_CONTRACT[type]?.outputs ?? 0;
  if (outputs === 0) return [];
  if (type === 'condition') return [HANDLE_TRUE, HANDLE_FALSE];
  // classifier 的真实把手依赖 config.classes，静态入口只给兜底 default
  if (type === 'classifier') return [HANDLE_CLASSIFIER_DEFAULT];
  return [HANDLE_OUT];
}

/** classifier.classes 中的合法 class 名（去重、保序、最多 10 个；UI 预览与把手共用） */
export function classifierClassNamesOf(config: unknown): string[] {
  const raw = (config as { classes?: unknown } | undefined)?.classes;
  if (!Array.isArray(raw)) return [];
  const names: string[] = [];
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue;
    const name = typeof (item as { name?: unknown }).name === 'string'
      ? (item as { name: string }).name.trim()
      : '';
    if (!name || !CLASSIFIER_NAME_PATTERN.test(name) || names.includes(name)) continue;
    names.push(name);
    if (names.length >= MAX_CLASSIFIER_CLASSES) break;
  }
  return names;
}

export interface NodeHandleSource {
  type?: WorkflowNodeType;
  nodeType?: WorkflowNodeType;
  config?: Record<string, unknown>;
  classNames?: string[];
}

function normalizeHandleSource(
  source: WorkflowNodeType | NodeHandleSource | WorkflowNode
): { type: WorkflowNodeType; classNames: string[] } {
  if (typeof source === 'string') return { type: source, classNames: [] };
  const candidate = source as NodeHandleSource;
  const type = (candidate.nodeType ?? candidate.type ?? 'script-exec') as WorkflowNodeType;
  const explicit = candidate.classNames;
  const classNames =
    explicit && explicit.length > 0
      ? explicit.filter((name) => CLASSIFIER_NAME_PATTERN.test(name)).slice(0, MAX_CLASSIFIER_CLASSES)
      : classifierClassNamesOf(candidate.config);
  return { type, classNames };
}

/**
 * 节点实例的动态 source handle（condition / classifier 不再是静态契约）：
 * - condition → true / false
 * - classifier → 配置里的每个 class 名 + default
 * - end → []
 * - 其余 → out
 */
export function sourceHandlesForNode(
  source: WorkflowNodeType | NodeHandleSource | WorkflowNode
): string[] {
  const { type, classNames } = normalizeHandleSource(source);
  if (type === 'end') return [];
  if (type === 'condition') return [HANDLE_TRUE, HANDLE_FALSE];
  if (type === 'classifier') return [...classNames, HANDLE_CLASSIFIER_DEFAULT];
  return [HANDLE_OUT];
}

/** classifier 的每个出边 handle（class / default）最多一条出边 */
export function isClassifierHandle(
  source: { type?: WorkflowNodeType; nodeType?: WorkflowNodeType },
  sourceHandle: string | null | undefined
): boolean {
  const type = source.nodeType ?? source.type;
  return type === 'classifier' && !!sourceHandle;
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
  | 'START_HAS_INPUT'
  | 'END_HAS_OUTPUT'
  | 'SELF_CONNECTION'
  | 'UNKNOWN_HANDLE'
  | 'CLASSIFIER_HANDLE_TAKEN'
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
  // start 只有出边、end 只有入边（§2.1 图拓扑硬约束）
  if (targetNode.type === 'start') return 'START_HAS_INPUT';
  if (sourceNode.type === 'end') return 'END_HAS_OUTPUT';
  if (source === target) return 'SELF_CONNECTION';

  const handle = connection.sourceHandle || HANDLE_OUT;
  const sourceHandlesOfNode = sourceHandlesForNode(sourceNode);
  // classifier 的 class handle 由 config.classes 动态生成；不存在/已改名的把手一律拒绝，
  // 不能静默回落到 true/false（那是 condition 的语义）。
  if (!sourceHandlesOfNode.includes(handle)) return 'UNKNOWN_HANDLE';
  const duplicate = context.edges.some(
    (e) => e.from === source && e.to === target && edgeHandleOf(context.nodes, e) === handle
  );
  if (duplicate) return 'DUPLICATE_EDGE';
  if (
    isClassifierHandle(sourceNode, handle) &&
    context.edges.some((e) => e.from === source && edgeHandleOf(context.nodes, e) === handle)
  ) {
    // 每个出边分支最多一条出边（与 DagValidator 的 classifier handle 校验一致）
    return 'CLASSIFIER_HANDLE_TAKEN';
  }
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
  if (!sourceType || sourceType === 'classifier') return false;
  return !isFalseBranchHandle(edge.sourceHandle, sourceType);
}

/* ── definition <-> React Flow 互转 ─────────────────────────────────── */

/**
 * 节点卡片摘要字段（structured，不预先生成本地化文案）：画布用这些字段渲染
 * 「输入 → 输出」一行；UI 层的变量选择器/检查器可以复用同一份数据。
 */
export interface WorkflowNodeData {
  key: string;
  nodeType: WorkflowNodeType;
  name: string;
  /** 运行态着色（编辑器态为 undefined） */
  status?: string;
  outputVar?: string;
  /** nodes.<key>.output —— 节点输出路径（start 为入参别名） */
  outputPath?: string;
  /** start：definition.variables 的数量 */
  inputCount?: number;
  /** end：config.outputs 的数量 */
  outputCount?: number;
  /** transform：config.mappings 的数量 */
  mappingCount?: number;
  /** script-exec：脚本第一行 */
  scriptPreview?: string;
  /** ai-analyze：指令第一行 */
  aiInstructionsPreview?: string;
  /** llm：provider/model（缺省时为空） */
  providerModel?: string;
  /** llm：userPrompt 第一行 */
  userPromptPreview?: string;
  /** classifier：class 数量与名单（画布直接渲染动态把手） */
  classCount?: number;
  classNames?: string[];
  /** code：语言与代码第一行 */
  language?: string;
  codePreview?: string;
  /** condition：条件表达式 */
  expression?: string;
  /** notify：级别 */
  notifyLevel?: string;
  /** notify：渠道数量 */
  channelCount?: number;
  /** approval：审批标题 */
  approvalTitle?: string;
  /** wait：等待秒数 */
  durationSec?: number;
  /** 画布直接渲染的一行中性摘要（由上面的结构化字段派生） */
  summary?: string;
  [extra: string]: unknown;
}

export type WorkflowFlowNode = Node<WorkflowNodeData, 'workflowNode'>;
export type WorkflowFlowEdge = Edge<
  { condition?: string; onUpstreamFailure?: string; sourceType?: WorkflowNodeType },
  'workflowEdge'
>;

export type WorkflowNodeSummary = Pick<
  WorkflowNodeData,
  | 'inputCount'
  | 'outputCount'
  | 'mappingCount'
  | 'scriptPreview'
  | 'aiInstructionsPreview'
  | 'providerModel'
  | 'userPromptPreview'
  | 'classCount'
  | 'classNames'
  | 'language'
  | 'codePreview'
  | 'expression'
  | 'notifyLevel'
  | 'channelCount'
  | 'approvalTitle'
  | 'durationSec'
>;

function configString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function configListLength(value: unknown): number {
  return Array.isArray(value) ? value.length : 0;
}

/** 第一行 + 72 字符截断（摘要只解释"这个节点做什么"，完整内容在检查器里） */
export function firstLineOf(value: unknown): string | undefined {
  const text = configString(value)?.trim();
  if (!text) return undefined;
  const line = text.split(/\r?\n/, 1)[0].trim();
  return line.length > 72 ? `${line.slice(0, 71)}…` : line;
}

/** 从节点定义提取卡片摘要字段（纯函数，definitionToFlow 与新建/粘贴共用） */
export function nodeSummaryOf(
  node: WorkflowNode,
  variables: Record<string, VariableSpec> = {}
): WorkflowNodeSummary {
  const config = node.config || {};
  switch (node.type) {
    case 'start':
      return { inputCount: Object.keys(variables || {}).length };
    case 'end':
      return { outputCount: configListLength(config.outputs) };
    case 'transform':
      return { mappingCount: configListLength(config.mappings) };
    case 'condition':
      return { expression: configString(config.expression) };
    case 'script-exec':
      return { scriptPreview: firstLineOf(config.script) };
    case 'ai-analyze':
      return { aiInstructionsPreview: firstLineOf(config.instructions) };
    case 'llm': {
      const provider = configString(config.provider);
      const model = configString(config.model);
      return {
        providerModel: provider || model ? [provider, model].filter(Boolean).join('/') : undefined,
        userPromptPreview: firstLineOf(config.userPrompt),
      };
    }
    case 'classifier': {
      const classNames = classifierClassNamesOf(config);
      return { classCount: classNames.length, classNames };
    }
    case 'code': {
      const language = configString(config.language);
      return {
        language: language === 'python' ? 'python' : language === 'javascript' ? 'javascript' : undefined,
        codePreview: firstLineOf(config.code),
      };
    }
    case 'notify':
      return {
        notifyLevel: configString(config.level) || 'info',
        channelCount: configListLength(config.channels),
      };
    case 'approval':
      return { approvalTitle: firstLineOf(config.title) || configString(config.title) };
    case 'wait':
      return {
        durationSec:
          typeof config.durationSec === 'number' && Number.isFinite(config.durationSec)
            ? config.durationSec
            : undefined,
      };
    default:
      return {};
  }
}

/** 中性摘要文本：数据口径（in:/out:/map:/ch），不依赖 i18n，UI 后续可整体替换 */
export function nodeSummaryTextOf(type: WorkflowNodeType, summary: WorkflowNodeSummary): string {
  switch (type) {
    case 'start':
      return summary.inputCount == null ? '' : `in: ${summary.inputCount}`;
    case 'end':
      return summary.outputCount == null ? '' : `out: ${summary.outputCount}`;
    case 'transform':
      return summary.mappingCount == null ? '' : `map: ${summary.mappingCount}`;
    case 'condition':
      return summary.expression || '';
    case 'script-exec':
      return summary.scriptPreview || '';
    case 'ai-analyze':
      return summary.aiInstructionsPreview || '';
    case 'llm':
      return summary.providerModel || summary.userPromptPreview || '';
    case 'classifier':
      return summary.classCount == null ? '' : `${summary.classCount} classes`;
    case 'code':
      return [summary.language, summary.codePreview].filter(Boolean).join(' · ');
    case 'notify':
      return [summary.notifyLevel || 'info', `${summary.channelCount ?? 0} ch`].join(' · ');
    case 'approval':
      return summary.approvalTitle || '';
    case 'wait':
      return summary.durationSec == null ? '' : `${summary.durationSec}s`;
    default:
      return '';
  }
}

/** WorkflowNode → React Flow node（新建/粘贴/definitionToFlow 共用，摘要随 config 更新） */
export function flowNodeOf(
  node: WorkflowNode,
  variables: Record<string, VariableSpec> = {}
): WorkflowFlowNode {
  const summary = nodeSummaryOf(node, variables);
  return {
    id: node.key,
    type: 'workflowNode',
    position: { x: node.position?.x ?? 0, y: node.position?.y ?? 0 },
    data: {
      key: node.key,
      nodeType: node.type,
      name: node.name,
      status: undefined,
      outputVar: node.outputVar,
      outputPath: `nodes.${node.key}.output`,
      ...summary,
      summary: nodeSummaryTextOf(node.type, summary),
    },
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    deletable: true,
  };
}

/**
 * 运行监控着色：statuses 缺省时原样返回 nodes（编辑态零克隆）。
 * 只克隆状态真实变化的节点，选中/拖拽数组身份不被无关节点打断。
 */
export function applyNodeStatuses(
  nodes: WorkflowFlowNode[],
  statuses?: Record<string, string>
): WorkflowFlowNode[] {
  if (!statuses) return nodes;
  let changed = false;
  const next = nodes.map((node) => {
    const status = statuses[node.id];
    if (status === undefined || status === node.data.status) return node;
    changed = true;
    return { ...node, data: { ...node.data, status } };
  });
  return changed ? next : nodes;
}

export function definitionToFlow(definition: WorkflowDefinition): {
  nodes: WorkflowFlowNode[];
  edges: WorkflowFlowEdge[];
} {
  const nodes: WorkflowFlowNode[] = (definition.nodes || []).map((node) =>
    flowNodeOf(node, definition.variables || {})
  );

  const edges: WorkflowFlowEdge[] = (definition.edges || []).map((edge) => {
    const sourceType = definition.nodes.find((node) => node.key === edge.from)?.type;
    const sourceHandle = edgeHandleOf(definition.nodes || [], edge);
    return {
      id: edge.id || edgeIdFor(edge.from, sourceHandle, edge.to),
      source: edge.from,
      target: edge.to,
      sourceHandle,
      type: 'workflowEdge',
      // 表达式只进 data（tooltip），不再把 512 字符原文贴在边上
      data: {
        condition: edge.condition,
        onUpstreamFailure: edge.onUpstreamFailure,
        sourceType,
      },
    };
  });

  return { nodes, edges };
}

/**
 * 出边 handle：新定义优先 edge.sourceHandle；旧定义回落到 condition 文本映射
 * （condition 节点：有表达式 = true 分支，无表达式 = false 分支）。
 */
export function edgeHandleOf(
  nodes: WorkflowNode[],
  edge: { from: string; sourceHandle?: string | null; condition?: string }
): string {
  if (edge.sourceHandle) return edge.sourceHandle;
  const source = nodes.find((n) => n.key === edge.from);
  if (source?.type === 'classifier') {
    // classifier 没有旧定义兼容语义：无 handle 时落到显式 default 兜底
    return HANDLE_CLASSIFIER_DEFAULT;
  }
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
      const sourceHandle = edgeHandleOf(definitionNodes, {
        from: e.source,
        sourceHandle: e.sourceHandle,
        condition: e.data?.condition,
      });
      const isFalse = isFalseBranchHandle(sourceHandle, sourceType);
      const condition =
        isFalse || sourceType === 'classifier' ? undefined : e.data?.condition || undefined;
      const existing = base.edges.find((edge) => edge.id === e.id);
      // 普通节点的 out 是缺省语义，不写进 definition（减少老定义的无意义 diff）；
      // condition 必须写 true/false，classifier 必须写 class/default，其他自定义 handle 原样保留。
      const writeHandle = sourceType === 'condition' || sourceType === 'classifier' || sourceHandle !== HANDLE_OUT;
      return {
        id: e.id || edgeIdFor(e.source, sourceHandle, e.target),
        from: e.source,
        to: e.target,
        ...(writeHandle ? { sourceHandle } : {}),
        ...(condition ? { condition } : {}),
        ...(e.data?.onUpstreamFailure ? { onUpstreamFailure: e.data.onUpstreamFailure as 'abort' | 'skip' | 'partial' } : {}),
        ...(existing?.onUpstreamFailure && !e.data?.onUpstreamFailure
          ? { onUpstreamFailure: existing.onUpstreamFailure }
          : {}),
        ...(existing?.condition && !condition && !isFalse && !e.data?.condition
          ? { condition: existing.condition }
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

/**
 * 新建节点默认值。键名与服务端执行器读取的一致（`script` / `targets` / `channels` /
 * `messageTemplate` / `expression` / `durationSec`）——前端写错一个字母，发布就是
 * `NODE_CONFIG_INVALID`，所以这里不做任何"更友好的别名"。
 */
export const DEFAULT_NODE_CONFIG: Record<WorkflowNodeType, Record<string, unknown>> = {
  start: {},
  'script-exec': {
    script: '',
    targets: { mode: 'explicit', agentIds: [] },
    parameters: {},
    rawParams: [],
    riskPolicy: 'block',
    onUnsafeValue: 'reject',
  },
  transform: { mappings: [] },
  condition: { expression: '' },
  'ai-analyze': {
    instructions: '',
    input: '',
    maxInputChars: 8000,
    outputMode: 'text',
    jsonSchema: '',
  },
  llm: {
    provider: null,
    model: null,
    systemPrompt: '',
    userPrompt: '',
    outputMode: 'text',
    jsonSchema: '',
    temperature: 0.3,
    maxInputChars: 8000,
  },
  classifier: {
    input: '',
    classes: [],
    instructions: '',
    provider: null,
    model: null,
  },
  code: {
    language: 'javascript',
    code: '',
    inputs: {},
    timeoutSec: 5,
  },
  notify: { channels: ['telegram'], messageTemplate: '', parameters: {} },
  approval: {
    mode: 'approval',
    title: '',
    approverRole: 'ADMIN',
    timeoutSec: 86400,
    onTimeout: 'reject',
    parameters: {},
  },
  wait: { durationSec: 60 },
  end: { outputs: [] },
};

/**
 * 节点默认超时：start/end/wait 是瞬时或自身有等待语义，交给引擎默认/自身字段；
 * transform 是纯内存映射，60s 足够；ai-analyze 受网络与模型延迟影响给 120s。
 */
const DEFAULT_TIMEOUT_SEC: Record<WorkflowNodeType, number | null> = {
  start: null,
  'script-exec': 600,
  transform: 60,
  condition: 600,
  'ai-analyze': 120,
  llm: 180,
  classifier: 120,
  code: 30,
  approval: 600,
  notify: 600,
  wait: null,
  end: null,
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
    config: structuredClone(DEFAULT_NODE_CONFIG[type]),
    retryMax: 0,
    retryIntervalSec: 5,
    timeoutSec: DEFAULT_TIMEOUT_SEC[type],
    onFailure: 'abort',
  };
}

/* ── 入口/出口节点（新建工作流自动补齐，旧工作流一键补全） ──────────── */

export interface StartEndNodes {
  start: WorkflowNode;
  end: WorkflowNode;
}

function unusedKey(preferred: string, used: Set<string>): string {
  if (!used.has(preferred)) {
    used.add(preferred);
    return preferred;
  }
  const key = nextNodeKey(used);
  used.add(key);
  return key;
}

/**
 * 创建一组开始/结束节点。key 优先用语义化 `start` / `end`（被占用时回落 n1/n2），
 * 名称不写死中文——调用方可用 i18n 文案覆盖。
 */
export function createStartAndEndNodes(
  existingKeys: Iterable<string> = [],
  startPosition: { x: number; y: number } = { x: 80, y: 80 },
  endPosition: { x: number; y: number } = { x: 900, y: 80 }
): StartEndNodes {
  const used = new Set(existingKeys);
  const startKey = unusedKey('start', used);
  const endKey = unusedKey('end', used);
  return {
    start: createWorkflowNode('start', startKey, startPosition, 'Start'),
    end: createWorkflowNode('end', endKey, endPosition, 'End'),
  };
}

export interface EnsureEntryExitResult {
  definition: WorkflowDefinition;
  addedKeys: string[];
  changed: boolean;
}

/**
 * 旧工作流补全入口/出口：只补节点，不擅自连线（入口连谁、出口从谁汇总由 UI 决定）。
 * 位置按现有节点包围盒左右各留 320px；空工作流用 80/900 的默认落点。
 */
export function ensureEntryExit(definition: WorkflowDefinition): EnsureEntryExitResult {
  const hasStart = definition.nodes.some((node) => node.type === 'start');
  const hasEnd = definition.nodes.some((node) => node.type === 'end');
  if (hasStart && hasEnd) return { definition, addedKeys: [], changed: false };

  const used = new Set(definition.nodes.map((node) => node.key));
  const xs = definition.nodes.map((node) => node.position?.x ?? 0);
  const ys = definition.nodes.map((node) => node.position?.y ?? 0);
  const startX = xs.length > 0 ? Math.min(...xs) - 320 : 80;
  const endX = xs.length > 0 ? Math.max(...xs) + 320 : 900;
  const edgeY = ys.length > 0 ? Math.min(...ys) : 80;

  const added: WorkflowNode[] = [];
  const addedKeys: string[] = [];

  if (!hasStart) {
    const key = unusedKey('start', used);
    added.push(createWorkflowNode('start', key, { x: startX, y: edgeY }, 'Start'));
    addedKeys.push(key);
  }
  if (!hasEnd) {
    const key = unusedKey('end', used);
    added.push(createWorkflowNode('end', key, { x: endX, y: edgeY }, 'End'));
    addedKeys.push(key);
  }

  return {
    definition: { ...definition, nodes: [...definition.nodes, ...added] },
    addedKeys,
    changed: added.length > 0,
  };
}
