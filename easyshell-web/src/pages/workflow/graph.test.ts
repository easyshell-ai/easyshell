import { describe, expect, it } from 'vitest';
import {
  DEFAULT_NODE_CONFIG,
  HANDLE_CLASSIFIER_DEFAULT,
  HANDLE_CONTRACT,
  HANDLE_FALSE,
  HANDLE_OUT,
  HANDLE_TRUE,
  applyNodeStatuses,
  autoLayoutPositions,
  checkConnection,
  classifierClassNamesOf,
  computeDeleteImpact,
  createStartAndEndNodes,
  createWorkflowNode,
  definitionToFlow,
  edgeHandleOf,
  edgeIdFor,
  ensureEntryExit,
  flowToDefinition,
  flowNodeOf,
  isValidNodeKey,
  nextNodeKey,
  nodeHeight,
  nodeSummaryOf,
  nodeSummaryTextOf,
  sourceHandles,
  sourceHandlesForNode,
  wouldCreateCycle,
} from './graph';
import type { WorkflowDefinition, WorkflowEdge, WorkflowNode, WorkflowNodeType } from './types';

function node(key: string, type: WorkflowNodeType = 'script-exec'): WorkflowNode {
  return { key, type, name: key, position: { x: 0, y: 0 }, config: {} };
}

function edge(from: string, to: string, condition?: string): WorkflowEdge {
  return { id: edgeIdFor(from, condition ? HANDLE_TRUE : HANDLE_OUT, to), from, to, condition };
}

function definition(overrides: Partial<WorkflowDefinition> = {}): WorkflowDefinition {
  return {
    schemaVersion: 1,
    defaultOnUpstreamFailure: 'skip',
    onRejection: 'abort',
    variables: {},
    nodes: [],
    edges: [],
    ...overrides,
  };
}

describe('node key generation', () => {
  it('starts at n1 and skips used keys', () => {
    expect(nextNodeKey([])).toBe('n1');
    expect(nextNodeKey(['n1', 'n2'])).toBe('n3');
    expect(nextNodeKey(['n2'])).toBe('n1');
    expect(nextNodeKey(['n1', 'a1', 'n3'])).toBe('n2');
  });

  it('enforces the ^[a-z][a-z0-9_]{0,63}$ contract', () => {
    expect(isValidNodeKey('n1')).toBe(true);
    expect(isValidNodeKey('fetch_logs')).toBe(true);
    expect(isValidNodeKey('N1')).toBe(false);
    expect(isValidNodeKey('1n')).toBe(false);
    expect(isValidNodeKey('a-b')).toBe(false);
    expect(isValidNodeKey('')).toBe(false);
  });
});

describe('handle contract', () => {
  it('start has 0 in / 1 out, end has 1 in / 0 out, condition 2 out, others 1/1', () => {
    expect(HANDLE_CONTRACT.start).toEqual({ inputs: 0, outputs: 1, height: 72 });
    expect(HANDLE_CONTRACT.end).toEqual({ inputs: 1, outputs: 0, height: 72 });
    expect(HANDLE_CONTRACT.condition).toEqual({ inputs: 1, outputs: 2, height: 96 });
    for (const type of [
      'script-exec',
      'transform',
      'ai-analyze',
      'llm',
      'code',
      'notify',
      'wait',
      'approval',
    ] as WorkflowNodeType[]) {
      expect(HANDLE_CONTRACT[type].outputs).toBe(1);
      expect(HANDLE_CONTRACT[type].inputs).toBe(1);
    }
    expect(HANDLE_CONTRACT.classifier.inputs).toBe(1);
    expect(nodeHeight('condition')).toBe(96);
    expect(nodeHeight('approval')).toBe(96);
    expect(nodeHeight('script-exec')).toBe(72);
    expect(nodeHeight('classifier', 11)).toBeGreaterThan(96);
    expect(nodeHeight('classifier', 3)).toBe(96);
  });

  it('exposes true/false handles only for condition and none for end', () => {
    expect(sourceHandles('condition')).toEqual([HANDLE_TRUE, HANDLE_FALSE]);
    expect(sourceHandles('classifier')).toEqual([HANDLE_CLASSIFIER_DEFAULT]);
    expect(sourceHandles('script-exec')).toEqual([HANDLE_OUT]);
    expect(sourceHandles('start')).toEqual([HANDLE_OUT]);
    expect(sourceHandles('end')).toEqual([]);
  });

  it('derives classifier class handles dynamically from config.classes', () => {
    const classifier = node('pick', 'classifier');
    classifier.config = {
      classes: [
        { name: 'disk', description: 'disk issues' },
        { name: 'network' },
        { name: 'disk', description: 'duplicate is dropped' },
        { name: 'Bad-Name' },
      ],
    };
    expect(classifierClassNamesOf(classifier.config)).toEqual(['disk', 'network']);
    expect(sourceHandlesForNode(classifier)).toEqual(['disk', 'network', HANDLE_CLASSIFIER_DEFAULT]);
    // 空配置时仍有 default 兜底
    expect(sourceHandlesForNode(node('empty', 'classifier'))).toEqual([HANDLE_CLASSIFIER_DEFAULT]);
    // 静态入口与其它类型保持兼容
    expect(sourceHandlesForNode('condition')).toEqual([HANDLE_TRUE, HANDLE_FALSE]);
    expect(sourceHandlesForNode('llm')).toEqual([HANDLE_OUT]);
    expect(sourceHandlesForNode('end')).toEqual([]);
  });
});

describe('cycle detection', () => {
  const edges: WorkflowEdge[] = [edge('a', 'b'), edge('b', 'c')];

  it('rejects closing a loop', () => {
    expect(wouldCreateCycle(edges, 'c', 'a')).toBe(true);
    expect(wouldCreateCycle(edges, 'a', 'a')).toBe(true);
  });

  it('allows forward and cross edges', () => {
    expect(wouldCreateCycle(edges, 'a', 'c')).toBe(false);
    expect(wouldCreateCycle(edges, 'c', 'd')).toBe(false);
  });
});

describe('checkConnection', () => {
  const context = {
    nodes: [node('a'), node('b'), node('c'), node('cond', 'condition'), node('x')],
    edges: [edge('a', 'b')],
  };

  it('rejects self connection', () => {
    expect(checkConnection(context, { source: 'a', target: 'a' })).toBe('SELF_CONNECTION');
  });

  it('rejects duplicate edges', () => {
    expect(checkConnection(context, { source: 'a', target: 'b', sourceHandle: HANDLE_OUT })).toBe('DUPLICATE_EDGE');
  });

  it('rejects cycles', () => {
    expect(checkConnection(context, { source: 'b', target: 'a' })).toBe('CYCLE');
  });

  it('rejects unknown endpoints', () => {
    expect(checkConnection(context, { source: 'a', target: 'zzz' })).toBe('ENDPOINT_MISSING');
  });

  it('allows a valid new edge', () => {
    expect(checkConnection(context, { source: 'a', target: 'c' })).toBeNull();
  });

  it('allows both condition branches onto the same target (not a duplicate)', () => {
    const withTrue: WorkflowEdge[] = [{ id: 'cond:true->x', from: 'cond', to: 'x', condition: 'a>1' }];
    const ctx = { nodes: context.nodes, edges: withTrue };
    expect(checkConnection(ctx, { source: 'cond', target: 'x', sourceHandle: HANDLE_FALSE })).toBeNull();
    expect(checkConnection(ctx, { source: 'cond', target: 'x', sourceHandle: HANDLE_TRUE })).toBe('DUPLICATE_EDGE');
  });

  it('validates classifier class handles and keeps one edge per class', () => {
    const classifier: WorkflowNode = {
      ...node('pick', 'classifier'),
      config: { classes: [{ name: 'disk' }, { name: 'network' }] },
    };
    const ctx = {
      nodes: [classifier, node('a'), node('b'), node('c')],
      edges: [] as WorkflowEdge[],
    };
    expect(checkConnection(ctx, { source: 'pick', target: 'a', sourceHandle: 'disk' })).toBeNull();
    expect(checkConnection(ctx, { source: 'pick', target: 'a', sourceHandle: 'true' })).toBe('UNKNOWN_HANDLE');
    expect(checkConnection(ctx, { source: 'pick', target: 'a', sourceHandle: 'default' })).toBeNull();

    const withDisk = {
      ...ctx,
      edges: [{ id: 'pick:disk->a', from: 'pick', to: 'a', sourceHandle: 'disk' }],
    };
    // 同一 class 换目标：拒绝；同一 class 同一目标：重复边；default 同样只允许一条出边
    expect(checkConnection(withDisk, { source: 'pick', target: 'b', sourceHandle: 'disk' })).toBe(
      'CLASSIFIER_HANDLE_TAKEN'
    );
    expect(checkConnection(withDisk, { source: 'pick', target: 'a', sourceHandle: 'disk' })).toBe('DUPLICATE_EDGE');
    expect(checkConnection(withDisk, { source: 'pick', target: 'b', sourceHandle: 'default' })).toBeNull();
    expect(checkConnection(withDisk, { source: 'pick', target: 'b', sourceHandle: 'network' })).toBeNull();

    const withDefault = {
      ...withDisk,
      edges: [...withDisk.edges, { id: 'pick:default->b', from: 'pick', to: 'b', sourceHandle: 'default' }],
    };
    expect(checkConnection(withDefault, { source: 'pick', target: 'c', sourceHandle: 'default' })).toBe(
      'CLASSIFIER_HANDLE_TAKEN'
    );
  });

  it('rejects any edge into start and any edge out of end', () => {
    const ctx = {
      nodes: [...context.nodes, node('start', 'start'), node('end', 'end')],
      edges: [],
    };
    expect(checkConnection(ctx, { source: 'a', target: 'start' })).toBe('START_HAS_INPUT');
    expect(checkConnection(ctx, { source: 'start', target: 'start' })).toBe('START_HAS_INPUT');
    expect(checkConnection(ctx, { source: 'end', target: 'a' })).toBe('END_HAS_OUTPUT');
    expect(checkConnection(ctx, { source: 'end', target: 'end' })).toBe('END_HAS_OUTPUT');
  });

  it('allows start as source and end as target', () => {
    const ctx = {
      nodes: [...context.nodes, node('start', 'start'), node('end', 'end')],
      edges: [],
    };
    expect(checkConnection(ctx, { source: 'start', target: 'a' })).toBeNull();
    expect(checkConnection(ctx, { source: 'a', target: 'end' })).toBeNull();
  });
});

describe('definition <-> flow roundtrip', () => {
  it('preserves positions, config, meta and edge conditions', () => {
    const original = definition({
      nodes: [
        { ...node('a', 'script-exec'), position: { x: 40, y: 60 }, config: { script: 'ls' }, retryMax: 2 },
        { ...node('cond', 'condition'), position: { x: 400, y: 0 }, config: { expression: 'a>1' } },
      ],
      edges: [
        { id: 'a:out->cond', from: 'a', to: 'cond' },
        { id: 'cond:true->b', from: 'cond', to: 'b', condition: 'a>1' },
      ],
      variables: { env: { type: 'string', required: true } },
    });
    // b 不存在时边被丢弃，补一个节点让 roundtrip 完整
    original.nodes.push(node('b'));

    const { nodes, edges } = definitionToFlow(original);
    expect(nodes).toHaveLength(3);
    expect(edges).toHaveLength(2);
    expect(edges.find((e) => e.id === 'cond:true->b')?.sourceHandle).toBe(HANDLE_TRUE);
    expect(edges.find((e) => e.id === 'a:out->cond')?.sourceHandle).toBe(HANDLE_OUT);
    // 表达式只进 data(tooltip)，不再用 label 常驻在边上
    expect(edges.find((e) => e.id === 'cond:true->b')?.label).toBeUndefined();
    expect(edges.find((e) => e.id === 'cond:true->b')?.data?.condition).toBe('a>1');

    const back = flowToDefinition(nodes, edges, original);
    expect(back.nodes.map((n) => n.key)).toEqual(['a', 'cond', 'b']);
    expect(back.nodes[0].config).toEqual({ script: 'ls' });
    expect(back.nodes[0].retryMax).toBe(2);
    expect(back.nodes[0].position).toEqual({ x: 40, y: 60 });
    expect(back.variables).toEqual({ env: { type: 'string', required: true } });
    expect(back.edges.find((e) => e.from === 'cond' && e.to === 'b')?.condition).toBe('a>1');
    expect(back.edges.find((e) => e.from === 'cond' && e.to === 'b')?.sourceHandle).toBe(HANDLE_TRUE);
    expect(back.edges.find((e) => e.from === 'a' && e.to === 'cond')?.condition).toBeUndefined();
    // 普通节点的 out 是缺省语义：definition 不再写冗余 sourceHandle
    expect(back.edges.find((e) => e.from === 'a' && e.to === 'cond')?.sourceHandle).toBeUndefined();
  });

  it('drops edges whose endpoints were removed', () => {
    const base = definition({ nodes: [node('a'), node('b')], edges: [edge('a', 'b')] });
    const { nodes, edges } = definitionToFlow(base);
    const onlyA = nodes.filter((n) => n.id === 'a');
    const back = flowToDefinition(onlyA, edges, base);
    expect(back.nodes).toHaveLength(1);
    expect(back.edges).toHaveLength(0);
  });

  it('keeps a false-branch edge unconditional', () => {
    const base = definition({ nodes: [node('cond', 'condition'), node('b')] });
    const { nodes, edges } = definitionToFlow(base);
    edges.push({
      id: 'cond:false->b',
      source: 'cond',
      target: 'b',
      sourceHandle: HANDLE_FALSE,
      type: 'workflowEdge',
      data: { condition: 'ignored' },
    });
    const back = flowToDefinition(nodes, edges, base);
    expect(back.edges[0].condition).toBeUndefined();
    expect(back.edges[0].sourceHandle).toBe(HANDLE_FALSE);
    expect(edgeHandleOf(back.nodes, back.edges[0])).toBe(HANDLE_FALSE);
  });

  it('prefers an explicit sourceHandle over the legacy condition mapping', () => {
    const base = definition({
      nodes: [node('cond', 'condition'), node('b')],
      edges: [{ id: 'cond:false->b', from: 'cond', to: 'b', sourceHandle: HANDLE_FALSE, condition: 'a>1' }],
    });
    const { edges } = definitionToFlow(base);
    expect(edges[0].sourceHandle).toBe(HANDLE_FALSE);
    expect(edgeHandleOf(base.nodes, base.edges[0])).toBe(HANDLE_FALSE);
    const back = flowToDefinition(definitionToFlow(base).nodes, edges, base);
    expect(back.edges[0].sourceHandle).toBe(HANDLE_FALSE);
    expect(back.edges[0].condition).toBeUndefined();
  });

  it('preserves classifier class handles through definition <-> flow roundtrip', () => {
    const base = definition({
      nodes: [
        {
          ...node('pick', 'classifier'),
          config: { classes: [{ name: 'disk' }, { name: 'network' }] },
        },
        node('a'),
        node('b'),
      ],
      edges: [
        { id: 'pick:disk->a', from: 'pick', to: 'a', sourceHandle: 'disk' },
        { id: 'pick:default->b', from: 'pick', to: 'b', sourceHandle: HANDLE_CLASSIFIER_DEFAULT },
      ],
    });
    const flow = definitionToFlow(base);
    expect(flow.edges.map((e) => e.sourceHandle)).toEqual(['disk', HANDLE_CLASSIFIER_DEFAULT]);
    const back = flowToDefinition(flow.nodes, flow.edges, base);
    expect(back.edges.map((e) => e.sourceHandle)).toEqual(['disk', HANDLE_CLASSIFIER_DEFAULT]);
    expect(back.edges[0].condition).toBeUndefined();
  });

  it('falls back to condition text for legacy condition nodes without sourceHandle', () => {
    const nodes = [node('cond', 'condition'), node('a'), node('b')];
    expect(edgeHandleOf(nodes, { from: 'cond', condition: 'x>0' })).toBe(HANDLE_TRUE);
    expect(edgeHandleOf(nodes, { from: 'cond' })).toBe(HANDLE_FALSE);
    expect(edgeHandleOf(nodes, { from: 'a' })).toBe(HANDLE_OUT);
  });

  it('normalizes legacy condition edges once and is then stable', () => {
    const legacy = definition({
      nodes: [node('cond', 'condition'), node('b')],
      edges: [{ id: 'cond->b', from: 'cond', to: 'b', condition: 'x>0' }],
    });
    const firstFlow = definitionToFlow(legacy);
    const first = flowToDefinition(firstFlow.nodes, firstFlow.edges, legacy);
    expect(first.edges[0].sourceHandle).toBe(HANDLE_TRUE);
    expect(first.edges[0].condition).toBe('x>0');

    const secondFlow = definitionToFlow(first);
    const second = flowToDefinition(secondFlow.nodes, secondFlow.edges, first);
    expect(second).toEqual(first);
  });
});

describe('auto layout', () => {
  it('layers nodes by depth using the shared DAG depth algorithm', () => {
    const nodes = [node('a'), node('b'), node('c'), node('d')];
    const edges = [edge('a', 'b'), edge('a', 'c'), edge('b', 'd'), edge('c', 'd')];
    const positions = autoLayoutPositions(nodes, edges);
    expect(positions.a.x).toBeLessThan(positions.b.x);
    expect(positions.b.x).toBe(positions.c.x);
    expect(positions.d.x).toBeGreaterThan(positions.c.x);
    expect(positions.a.y).toBe(positions.b.y - 0); // 同层首元素同一 y 起点
    expect(positions.c.y).toBeGreaterThan(positions.b.y);
    expect(positions.d.y).toBeLessThan(positions.c.y);
  });

  it('keeps isolated nodes on layer 0', () => {
    const positions = autoLayoutPositions([node('lonely')], []);
    expect(positions.lonely).toEqual({ x: 80, y: 80 });
  });
});

describe('delete impact', () => {
  it('collects the node and every edge touching it', () => {
    const edges = [edge('a', 'b'), edge('b', 'c'), edge('c', 'a')];
    const impact = computeDeleteImpact(['b'], edges);
    expect(impact.nodes).toEqual(['b']);
    expect(impact.edges).toEqual(['a:out->b', 'b:out->c']);
  });
});

describe('new node defaults', () => {
  it('has defaults for all 12 node types with server field names', () => {
    const types: WorkflowNodeType[] = [
      'start',
      'script-exec',
      'transform',
      'condition',
      'ai-analyze',
      'llm',
      'classifier',
      'code',
      'approval',
      'notify',
      'wait',
      'end',
    ];
    for (const type of types) expect(DEFAULT_NODE_CONFIG[type]).toBeDefined();
    expect(DEFAULT_NODE_CONFIG.transform).toEqual({ mappings: [] });
    expect(DEFAULT_NODE_CONFIG['ai-analyze']).toMatchObject({ maxInputChars: 8000, outputMode: 'text' });
    expect(DEFAULT_NODE_CONFIG.llm).toMatchObject({
      provider: null,
      model: null,
      outputMode: 'text',
      temperature: 0.3,
      maxInputChars: 8000,
    });
    expect(DEFAULT_NODE_CONFIG.classifier).toMatchObject({ classes: [], provider: null, model: null });
    expect(DEFAULT_NODE_CONFIG.code).toEqual({
      language: 'javascript',
      code: '',
      inputs: {},
      timeoutSec: 5,
    });
    expect(DEFAULT_NODE_CONFIG.end).toEqual({ outputs: [] });
  });

  it('sets sensible timeout defaults for instant and long-running node types', () => {
    expect(createWorkflowNode('start', 'start', { x: 0, y: 0 }).timeoutSec).toBeNull();
    expect(createWorkflowNode('end', 'end', { x: 0, y: 0 }).timeoutSec).toBeNull();
    expect(createWorkflowNode('wait', 'w', { x: 0, y: 0 }).timeoutSec).toBeNull();
    expect(createWorkflowNode('transform', 't', { x: 0, y: 0 }).timeoutSec).toBe(60);
    expect(createWorkflowNode('ai-analyze', 'ai', { x: 0, y: 0 }).timeoutSec).toBe(120);
    expect(createWorkflowNode('llm', 'llm', { x: 0, y: 0 }).timeoutSec).toBe(180);
    expect(createWorkflowNode('classifier', 'cls', { x: 0, y: 0 }).timeoutSec).toBe(120);
    expect(createWorkflowNode('code', 'code', { x: 0, y: 0 }).timeoutSec).toBe(30);
    expect(createWorkflowNode('script-exec', 's', { x: 0, y: 0 }).timeoutSec).toBe(600);
  });

  it('deep-clones default config so nodes cannot share mutable mappings', () => {
    const a = createWorkflowNode('transform', 'a', { x: 0, y: 0 });
    const b = createWorkflowNode('transform', 'b', { x: 0, y: 0 });
    (a.config.mappings as unknown[]).push({ name: 'x', value: 'y' });
    expect(b.config.mappings).toEqual([]);
  });
});

describe('entry / exit seeding', () => {
  it('creates semantic start/end keys and falls back when occupied', () => {
    const fresh = createStartAndEndNodes();
    expect([fresh.start.key, fresh.end.key]).toEqual(['start', 'end']);
    const used = createStartAndEndNodes(['start', 'end']);
    expect(used.start.key).toBe('n1');
    expect(used.end.key).toBe('n2');
  });

  it('ensureEntryExit adds missing nodes without touching edges', () => {
    const base = definition({
      nodes: [node('a'), node('b')],
      edges: [edge('a', 'b')],
    });
    const first = ensureEntryExit(base);
    expect(first.changed).toBe(true);
    expect(first.addedKeys).toEqual(['start', 'end']);
    expect(first.definition.nodes.map((n) => n.type)).toEqual([
      'script-exec',
      'script-exec',
      'start',
      'end',
    ]);
    expect(first.definition.edges).toEqual(base.edges);
    expect(first.definition.nodes.find((n) => n.type === 'start')!.position.x).toBeLessThan(0);
    expect(first.definition.nodes.find((n) => n.type === 'end')!.position.x).toBeGreaterThan(0);

    const again = ensureEntryExit(first.definition);
    expect(again.changed).toBe(false);
    expect(again.definition).toBe(first.definition);
  });

  it('seeds both ends into an empty workflow', () => {
    const result = ensureEntryExit(definition());
    expect(result.definition.nodes.map((n) => n.key)).toEqual(['start', 'end']);
    expect(result.definition.nodes[0].position).toEqual({ x: 80, y: 80 });
    expect(result.definition.nodes[1].position).toEqual({ x: 900, y: 80 });
  });
});

describe('node summary extraction', () => {
  it('derives structured summary fields from config', () => {
    expect(
      nodeSummaryOf({ ...node('s', 'start'), config: {} }, { env: { type: 'string' }, host: { type: 'string' } })
    ).toEqual({ inputCount: 2 });
    expect(
      nodeSummaryOf({ ...node('e', 'end'), config: { outputs: [{ name: 'a', value: '1' }] } })
    ).toEqual({ outputCount: 1 });
    expect(
      nodeSummaryOf({ ...node('t', 'transform'), config: { mappings: [{ name: 'a', value: '1' }] } })
    ).toEqual({ mappingCount: 1 });
    expect(
      nodeSummaryOf({ ...node('c', 'condition'), config: { expression: 'nodes.a.output.count > 0' } })
    ).toEqual({ expression: 'nodes.a.output.count > 0' });
    expect(
      nodeSummaryOf({
        ...node('x', 'script-exec'),
        config: { script: 'df -h\necho done' },
      })
    ).toEqual({ scriptPreview: 'df -h' });
    expect(
      nodeSummaryOf({
        ...node('ai', 'ai-analyze'),
        config: { instructions: '分析磁盘\n第二行' },
      })
    ).toEqual({ aiInstructionsPreview: '分析磁盘' });
    expect(
      nodeSummaryOf({ ...node('n', 'notify'), config: { channels: ['a', 'b'], level: 'warning' } })
    ).toEqual({ notifyLevel: 'warning', channelCount: 2 });
    expect(
      nodeSummaryOf({ ...node('ap', 'approval'), config: { title: '重启服务审批' } })
    ).toEqual({ approvalTitle: '重启服务审批' });
    expect(nodeSummaryOf({ ...node('w', 'wait'), config: { durationSec: 30 } })).toEqual({
      durationSec: 30,
    });
    expect(
      nodeSummaryOf({
        ...node('llm', 'llm'),
        config: { provider: 'openai', model: 'gpt-4o-mini', userPrompt: '分析磁盘\n第二行' },
      })
    ).toEqual({ providerModel: 'openai/gpt-4o-mini', userPromptPreview: '分析磁盘' });
    expect(
      nodeSummaryOf({
        ...node('pick', 'classifier'),
        config: { classes: [{ name: 'disk' }, { name: 'network' }, { name: 'disk' }] },
      })
    ).toEqual({ classCount: 2, classNames: ['disk', 'network'] });
    expect(
      nodeSummaryOf({
        ...node('code', 'code'),
        config: { language: 'python', code: 'def main(input):\n    return input' },
      })
    ).toEqual({ language: 'python', codePreview: 'def main(input):' });
  });

  it('renders neutral one-line text and truncates the first line', () => {
    expect(nodeSummaryTextOf('start', { inputCount: 3 })).toBe('in: 3');
    expect(nodeSummaryTextOf('end', { outputCount: 2 })).toBe('out: 2');
    expect(nodeSummaryTextOf('transform', { mappingCount: 4 })).toBe('map: 4');
    expect(nodeSummaryTextOf('wait', { durationSec: 60 })).toBe('60s');
    expect(nodeSummaryTextOf('notify', { notifyLevel: 'info', channelCount: 2 })).toBe('info · 2 ch');
    expect(nodeSummaryTextOf('classifier', { classCount: 3 })).toBe('3 classes');
    expect(nodeSummaryTextOf('code', { language: 'javascript', codePreview: 'function main()' })).toBe(
      'javascript · function main()'
    );
    const long = 'x'.repeat(120);
    const summary = nodeSummaryOf({ ...node('s'), config: { script: `${long}\nnext` } });
    expect(summary.scriptPreview).toHaveLength(72);
    expect(summary.scriptPreview!.endsWith('…')).toBe(true);
  });

  it('flowNodeOf carries summary + output path for the canvas card', () => {
    const flowNode = flowNodeOf(
      { ...node('s', 'start'), outputVar: 'entry' },
      { env: { type: 'string' } }
    );
    expect(flowNode.data).toMatchObject({
      key: 's',
      nodeType: 'start',
      outputVar: 'entry',
      outputPath: 'nodes.s.output',
      inputCount: 1,
      summary: 'in: 1',
    });
  });
});

describe('monitor status decoration', () => {
  const { nodes } = definitionToFlow(definition({ nodes: [node('a'), node('b')] }));

  it('returns the exact same array when statuses are absent (editor mode)', () => {
    expect(applyNodeStatuses(nodes, undefined)).toBe(nodes);
  });

  it('only clones nodes whose status actually changed', () => {
    const decorated = applyNodeStatuses(nodes, { a: 'running', b: undefined as unknown as string });
    expect(decorated[0]).not.toBe(nodes[0]);
    expect(decorated[0].data.status).toBe('running');
    expect(decorated[1]).toBe(nodes[1]);
    expect(applyNodeStatuses(nodes, {})).toBe(nodes);
  });
});
