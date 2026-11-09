import { describe, expect, it } from 'vitest';
import {
  autoLayoutPositions,
  checkConnection,
  computeDeleteImpact,
  definitionToFlow,
  edgeHandleOf,
  edgeIdFor,
  flowToDefinition,
  isValidNodeKey,
  nextNodeKey,
  nodeHeight,
  sourceHandles,
  wouldCreateCycle,
  HANDLE_CONTRACT,
  HANDLE_FALSE,
  HANDLE_OUT,
  HANDLE_TRUE,
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
  it('condition has 2 outputs and dual height, everything else 1 in / 1 out', () => {
    expect(HANDLE_CONTRACT.condition).toEqual({ inputs: 1, outputs: 2, height: 96 });
    for (const type of ['script-exec', 'notify', 'wait', 'approval'] as WorkflowNodeType[]) {
      expect(HANDLE_CONTRACT[type].outputs).toBe(1);
      expect(HANDLE_CONTRACT[type].inputs).toBe(1);
    }
    expect(nodeHeight('condition')).toBe(96);
    expect(nodeHeight('approval')).toBe(96);
    expect(nodeHeight('script-exec')).toBe(72);
  });

  it('exposes true/false handles only for condition', () => {
    expect(sourceHandles('condition')).toEqual([HANDLE_TRUE, HANDLE_FALSE]);
    expect(sourceHandles('script-exec')).toEqual([HANDLE_OUT]);
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

    const back = flowToDefinition(nodes, edges, original);
    expect(back.nodes.map((n) => n.key)).toEqual(['a', 'cond', 'b']);
    expect(back.nodes[0].config).toEqual({ script: 'ls' });
    expect(back.nodes[0].retryMax).toBe(2);
    expect(back.nodes[0].position).toEqual({ x: 40, y: 60 });
    expect(back.variables).toEqual({ env: { type: 'string', required: true } });
    expect(back.edges.find((e) => e.from === 'cond' && e.to === 'b')?.condition).toBe('a>1');
    expect(back.edges.find((e) => e.from === 'a' && e.to === 'cond')?.condition).toBeUndefined();
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
    expect(edgeHandleOf(back.nodes, back.edges[0])).toBe(HANDLE_FALSE);
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
