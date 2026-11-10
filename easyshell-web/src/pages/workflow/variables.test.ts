import { describe, expect, it } from 'vitest';
import {
  availableVariablesForNode,
  ancestorKeysOf,
  knownOutputFieldsOf,
  workflowVariablesOf,
} from './variables';
import type { WorkflowDefinition, WorkflowNode, WorkflowNodeType } from './types';

function node(
  key: string,
  type: WorkflowNodeType = 'script-exec',
  extra: Partial<WorkflowNode> = {}
): WorkflowNode {
  return { key, type, name: key, position: { x: 0, y: 0 }, config: {}, ...extra };
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

function def(): WorkflowDefinition {
  return definition({
    variables: {
      env: { type: 'string', required: true, description: '目标环境' },
      count: { type: 'number' },
    },
    nodes: [
      node('start', 'start', { name: '开始' }),
      node('fetch', 'script-exec', { name: '拉取', outputVar: 'raw' }),
      node('check', 'condition', {
        name: '判断',
        config: { expression: 'nodes.fetch.output.count > 0' },
      }),
      node('map', 'transform', {
        name: '映射',
        config: { mappings: [{ name: 'usage', value: '${nodes.check.output.result}' }] },
      }),
      node('ai', 'ai-analyze', { name: 'AI', config: { instructions: '分析' } }),
      node('done', 'end', { name: '结束' }),
    ],
    edges: [
      { id: 'start:out->fetch', from: 'start', to: 'fetch', sourceHandle: 'out' },
      { id: 'fetch:out->check', from: 'fetch', to: 'check', sourceHandle: 'out' },
      { id: 'check:true->map', from: 'check', to: 'map', sourceHandle: 'true', condition: 'nodes.fetch.output.count > 0' },
      { id: 'check:false->ai', from: 'check', to: 'ai', sourceHandle: 'false' },
      { id: 'map:out->done', from: 'map', to: 'done', sourceHandle: 'out' },
    ],
  });
}

describe('ancestorKeysOf', () => {
  it('collects transitive ancestors without the node itself', () => {
    expect([...ancestorKeysOf(def(), 'map')].sort()).toEqual(['check', 'fetch', 'start']);
    expect([...ancestorKeysOf(def(), 'start')]).toEqual([]);
  });

  it('is safe with unknown keys and legacy cycles', () => {
    expect([...ancestorKeysOf(def(), 'missing')]).toEqual([]);
    const cyclic = definition({
      nodes: [node('a'), node('b')],
      edges: [
        { id: 'a->b', from: 'a', to: 'b' },
        { id: 'b->a', from: 'b', to: 'a' },
      ],
    });
    expect([...ancestorKeysOf(cyclic, 'a')]).toEqual(['b']);
  });
});

describe('known output schema', () => {
  it('exposes ai-analyze structured JSON outputs and localizes every description key', () => {
    const ai = knownOutputFieldsOf(node('ai', 'ai-analyze'));
    expect(ai.map((field) => field.name)).toEqual(['text', 'json', 'structured', 'model', 'truncated']);
    expect(ai.find((field) => field.name === 'json')).toMatchObject({
      type: 'json',
      description: 'workflow.variable.output.ai-analyze.json',
    });
    expect(ai.find((field) => field.name === 'structured')).toMatchObject({
      type: 'boolean',
      description: 'workflow.variable.output.ai-analyze.structured',
    });

    const groups = workflowVariablesOf(
      definition({
        nodes: [node('ai', 'ai-analyze', { config: { outputMode: 'json' } })],
        edges: [],
      })
    );
    const refs = groups.flatMap((group) => group.refs.map((ref) => ref.path));
    expect(refs).toContain('nodes.ai.output.json');
    expect(refs).toContain('nodes.ai.output.structured');

    for (const type of [
      'condition',
      'notify',
      'approval',
      'wait',
      'ai-analyze',
      'llm',
      'classifier',
    ] as WorkflowNodeType[]) {
      for (const field of knownOutputFieldsOf(node(type, type))) {
        expect(field.description).toMatch(/^workflow\.variable\.output\./);
      }
    }
  });
});

describe('workflowVariablesOf', () => {
  it('groups run inputs, start outputs, node outputs and outputVar aliases', () => {
    const groups = workflowVariablesOf(def());
    const ids = groups.map((group) => group.id);
    expect(ids).toEqual(['run-input', 'node:start', 'node:fetch', 'node:check', 'node:map', 'node:ai', 'node:done', 'output-var']);

    const input = groups.find((group) => group.kind === 'input')!;
    expect(input.refs.map((ref) => ref.path)).toEqual(['run.input.env', 'run.input.count']);
    expect(input.refs[0]).toMatchObject({ type: 'string', required: true, description: '目标环境' });

    const start = groups.find((group) => group.nodeKey === 'start')!;
    expect(start.kind).toBe('start');
    expect(start.refs.map((ref) => ref.path)).toEqual([
      'nodes.start.output.env',
      'nodes.start.output.count',
    ]);

    const condition = groups.find((group) => group.nodeKey === 'check')!;
    expect(condition.refs.map((ref) => ref.path)).toEqual([
      'nodes.check.output',
      'nodes.check.output.result',
    ]);

    const transform = groups.find((group) => group.nodeKey === 'map')!;
    expect(transform.refs.map((ref) => ref.path)).toEqual([
      'nodes.map.output',
      'nodes.map.output.usage',
    ]);

    const alias = groups.find((group) => group.kind === 'alias')!;
    expect(alias.refs.map((ref) => ref.path)).toEqual(['raw']);
  });
});

describe('availableVariablesForNode', () => {
  it('only exposes topological ancestors plus workflow inputs', () => {
    const groups = availableVariablesForNode(def(), 'map');
    const nodeKeys = groups.filter((group) => group.kind === 'node').map((group) => group.nodeKey);
    expect(nodeKeys).toEqual(['fetch', 'check']);
    expect(groups.some((group) => group.kind === 'start')).toBe(true);
    expect(groups.some((group) => group.nodeKey === 'ai' || group.nodeKey === 'done')).toBe(false);

    const refs = groups.flatMap((group) => group.refs.map((ref) => ref.path));
    expect(refs).toContain('run.input.env');
    expect(refs).toContain('nodes.start.output.env');
    expect(refs).toContain('nodes.fetch.output');
    expect(refs).toContain('nodes.check.output.result');
    expect(refs).not.toContain('nodes.ai.output.text');
    expect(refs).not.toContain('nodes.map.output');
  });

  it('includes alias fields for ancestor nodes only', () => {
    const groups = availableVariablesForNode(def(), 'check');
    const alias = groups.find((group) => group.kind === 'alias')!;
    // fetch 是 check 的祖先；fetch 的 outputVar = raw，脚本输出无已知字段 → 只有整体别名
    expect(alias.refs.map((ref) => ref.path)).toEqual(['raw']);
    expect(alias.refs[0].nodeKey).toBe('fetch');
  });

  it('returns only workflow inputs for the start node', () => {
    const groups = availableVariablesForNode(def(), 'start');
    expect(groups).toHaveLength(2); // run.input + start outputs
    expect(groups[0].kind).toBe('input');
    expect(groups[1].kind).toBe('start');
  });
});
