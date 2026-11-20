import { beforeEach, describe, expect, it } from 'vitest';
import {
  buildClipboardPayload,
  parseClipboard,
  pasteWithNewKeys,
  resetClipboardFallback,
  serializeClipboard,
} from './clipboard';
import { edgeIdFor, HANDLE_FALSE, HANDLE_OUT, HANDLE_TRUE } from '../../graph';
import type { WorkflowEdge, WorkflowNode } from '../../types';

const node = (key: string, x = 0, y = 0, type: WorkflowNode['type'] = 'script-exec'): WorkflowNode => ({
  key,
  type,
  name: key,
  position: { x, y },
  config: {},
});

describe('clipboard payload', () => {
  it('keeps only edges internal to the copied set', () => {
    const edges: WorkflowEdge[] = [
      { id: edgeIdFor('a', HANDLE_OUT, 'b'), from: 'a', to: 'b' },
      { id: edgeIdFor('b', HANDLE_OUT, 'c'), from: 'b', to: 'c' },
    ];
    const payload = buildClipboardPayload([node('a'), node('b')], edges);
    expect(payload.nodes.map((n) => n.key)).toEqual(['a', 'b']);
    expect(payload.edges.map((e) => e.id)).toEqual(['a:out->b']);
  });

  it('survives a JSON roundtrip', () => {
    const payload = buildClipboardPayload([node('a', 10, 20)], []);
    const parsed = parseClipboard(serializeClipboard(payload));
    expect(parsed?.nodes[0].position).toEqual({ x: 10, y: 20 });
  });

  it('rejects text that is not our payload', () => {
    expect(parseClipboard('')).toBeNull();
    expect(parseClipboard('not json')).toBeNull();
    expect(parseClipboard('{"nodes":[]}')).toBeNull();
    expect(parseClipboard('{"foo":1}')).toBeNull();
  });
});

describe('pasteWithNewKeys', () => {
  beforeEach(() => resetClipboardFallback());

  it('regenerates keys that collide with the canvas', () => {
    const payload = buildClipboardPayload([node('n1', 100, 100), node('n2', 300, 100)], []);
    const result = pasteWithNewKeys(payload, ['n1', 'n2', 'n3']);
    expect(result.nodes.map((n) => n.key)).toEqual(['n4', 'n5']);
    expect(result.keyMap).toEqual({ n1: 'n4', n2: 'n5' });
  });

  it('keeps keys free when the canvas is empty', () => {
    const payload = buildClipboardPayload([node('n1'), node('n2')], []);
    const result = pasteWithNewKeys(payload, []);
    expect(result.nodes.map((n) => n.key)).toEqual(['n1', 'n2']);
  });

  it('offsets positions so the paste does not land exactly on the original', () => {
    const payload = buildClipboardPayload([node('n1', 100, 100)], []);
    const result = pasteWithNewKeys(payload, []);
    expect(result.nodes[0].position).toEqual({ x: 140, y: 140 });
  });

  it('remaps internal edges and their handles', () => {
    const edges: WorkflowEdge[] = [
      { id: edgeIdFor('cond', HANDLE_TRUE, 'b'), from: 'cond', to: 'b', condition: 'x>1' },
      { id: edgeIdFor('cond', HANDLE_FALSE, 'c'), from: 'cond', to: 'c' },
      { id: edgeIdFor('c', HANDLE_OUT, 'outside'), from: 'c', to: 'outside' },
    ];
    const payload = buildClipboardPayload(
      [node('cond', 0, 0, 'condition'), node('b'), node('c')],
      edges
    );
    const result = pasteWithNewKeys(payload, []);
    expect(result.edges).toHaveLength(2);
    const trueEdge = result.edges.find((e) => e.condition === 'x>1');
    expect(trueEdge?.from).toBe(result.keyMap.cond);
    expect(trueEdge?.to).toBe(result.keyMap.b);
    expect(trueEdge?.id).toBe(`${result.keyMap.cond}:${HANDLE_TRUE}->${result.keyMap.b}`);
    const falseEdge = result.edges.find((e) => !e.condition);
    expect(falseEdge?.id).toBe(`${result.keyMap.cond}:${HANDLE_FALSE}->${result.keyMap.c}`);
  });

  it('preserves classifier class/default handles instead of remapping them to true/false', () => {
    const classifier: WorkflowNode = {
      ...node('pick', 0, 0, 'classifier'),
      config: { classes: [{ name: 'disk' }, { name: 'network' }] },
    };
    const edges: WorkflowEdge[] = [
      { id: edgeIdFor('pick', 'disk', 'a'), from: 'pick', to: 'a', sourceHandle: 'disk' },
      { id: edgeIdFor('pick', 'default', 'b'), from: 'pick', to: 'b', sourceHandle: 'default' },
    ];
    const payload = buildClipboardPayload([classifier, node('a'), node('b')], edges);
    const result = pasteWithNewKeys(payload, []);
    const from = result.keyMap.pick;
    expect(result.edges.map((e) => e.sourceHandle)).toEqual(['disk', 'default']);
    expect(result.edges.map((e) => e.id)).toEqual([
      `${from}:disk->${result.keyMap.a}`,
      `${from}:default->${result.keyMap.b}`,
    ]);
  });

  it('drops edges pointing outside the copied set', () => {
    const payload = buildClipboardPayload([node('a')], [{ id: 'a:out->zz', from: 'a', to: 'zz' }]);
    const result = pasteWithNewKeys(payload, []);
    expect(result.edges).toEqual([]);
  });
});
