import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { WorkflowDefinition, WorkflowNode, WorkflowVersionDetail } from '../types';

const saveWorkflowDraft = vi.fn();
const getWorkflowDetail = vi.fn();

vi.mock('../api', () => ({
  saveWorkflowDraft: (...args: unknown[]) => saveWorkflowDraft(...args),
  getWorkflowDetail: (...args: unknown[]) => getWorkflowDetail(...args),
}));

import { SAVE_DEBOUNCE_MS, useWorkflowEditor, type EditorSnapshot } from './useWorkflowEditor';
import { definitionToFlow } from '../graph';

const definition: WorkflowDefinition = {
  schemaVersion: 1,
  defaultOnUpstreamFailure: 'skip',
  onRejection: 'abort',
  variables: {},
  nodes: [
    { key: 'n1', type: 'script-exec', name: 'first', position: { x: 0, y: 0 }, config: { script: 'ls' } },
  ],
  edges: [],
};

const draft: WorkflowVersionDetail = {
  id: 10,
  version: 4,
  baseVersion: 4,
  isValid: false,
  createdAt: '2026-01-01T00:00:00',
  definition,
};

function nextSnapshot(extraNodes: number = 0): EditorSnapshot {
  const { nodes, edges } = definitionToFlow(definition);
  const configs: Record<string, WorkflowNode> = { n1: definition.nodes[0] };
  for (let i = 0; i < extraNodes; i += 1) {
    const key = `n${nodes.length + 1}`;
    nodes.push({
      id: key,
      type: 'workflowNode',
      position: { x: 100 * (nodes.length + 1), y: 0 },
      data: { key, nodeType: 'script-exec', name: key },
    });
    configs[key] = { key, type: 'script-exec', name: key, position: { x: 100 * nodes.length, y: 0 }, config: {} };
  }
  return { nodes, edges, configs, meta: { defaultOnUpstreamFailure: 'skip', onRejection: 'abort', variables: {} } };
}

function setup() {
  const onVersionConflict = vi.fn();
  const onSaved = vi.fn();
  const view = renderHook(() =>
    useWorkflowEditor({ workflowId: 7, draft, onVersionConflict, onSaved })
  );
  return { ...view, onVersionConflict, onSaved };
}

describe('useWorkflowEditor', () => {
  beforeEach(() => {
    saveWorkflowDraft.mockReset();
    getWorkflowDetail.mockReset();
    saveWorkflowDraft.mockResolvedValue({ code: 200, message: 'ok', data: { id: 10, version: 5, baseVersion: 5 } });
    window.localStorage.clear();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('starts clean and mirrors the draft definition', () => {
    const { result } = setup();
    expect(result.current.dirty).toBe(false);
    expect(result.current.nodes).toHaveLength(1);
    expect(result.current.definition.nodes[0].config).toEqual({ script: 'ls' });
    expect(saveWorkflowDraft).not.toHaveBeenCalled();
  });

  it('marks dirty on commit and auto-saves after the 1.5s debounce', () => {
    const { result } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    expect(result.current.dirty).toBe(true);

    act(() => {
      vi.advanceTimersByTime(SAVE_DEBOUNCE_MS - 1);
    });
    expect(saveWorkflowDraft).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(2);
    });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(1);
    expect(saveWorkflowDraft.mock.calls[0][1].baseVersion).toBe(4);
    expect(saveWorkflowDraft.mock.calls[0][1].definition.nodes).toHaveLength(2);
  });

  it('resets the debounce when another change lands inside the window', () => {
    const { result } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    act(() => {
      result.current.commit('add-node', nextSnapshot(2));
    });
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(saveWorkflowDraft).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(SAVE_DEBOUNCE_MS - 1000);
    });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(1);
  });

  it('clears dirty after a successful save', async () => {
    const { result, onSaved } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    await act(async () => {
      vi.advanceTimersByTime(SAVE_DEBOUNCE_MS);
    });
    expect(result.current.dirty).toBe(false);
    expect(onSaved).toHaveBeenCalledWith({ id: 10, version: 5, baseVersion: 5 });
  });

  it('surfaces a 409 as a version conflict instead of overwriting', async () => {
    saveWorkflowDraft.mockResolvedValue({ code: 409, message: 'VERSION_CONFLICT', data: null });
    const { result, onVersionConflict } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    await act(async () => {
      vi.advanceTimersByTime(SAVE_DEBOUNCE_MS);
    });
    expect(onVersionConflict).toHaveBeenCalledWith({ localBaseVersion: 4 });
    expect(result.current.dirty).toBe(true);
  });

  it('persists a sanitized local draft when the save fails', async () => {
    saveWorkflowDraft.mockRejectedValue(new Error('offline'));
    const { result } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    await act(async () => {
      vi.advanceTimersByTime(SAVE_DEBOUNCE_MS);
    });
    const stored = window.localStorage.getItem('wf-draft-7');
    expect(stored).toBeTruthy();
    expect(JSON.parse(stored!).definition.nodes).toHaveLength(2);
    expect(result.current.localDraft?.baseVersion).toBe(4);
  });

  it('undo/redo walks structure changes and re-dirties the draft', () => {
    const { result } = setup();
    const before = { nodes: result.current.nodes, edges: result.current.edges };
    expect(result.current.canUndo).toBe(false);

    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    expect(result.current.canUndo).toBe(true);
    expect(result.current.nodes).toHaveLength(2);

    act(() => {
      result.current.undo();
    });
    expect(result.current.nodes).toEqual(before.nodes);
    expect(result.current.canRedo).toBe(true);
    expect(result.current.dirty).toBe(true);

    act(() => {
      result.current.redo();
    });
    expect(result.current.nodes).toHaveLength(2);
  });

  it('updateNode writes config through the snapshot and keeps it undoable', () => {
    const { result } = setup();
    act(() => {
      result.current.updateNode('n1', { config: { script: 'df -h' }, name: 'disk check' });
    });
    expect(result.current.definition.nodes[0].config).toEqual({ script: 'df -h' });
    expect(result.current.definition.nodes[0].name).toBe('disk check');
    expect(result.current.dirty).toBe(true);

    act(() => {
      result.current.undo();
    });
    expect(result.current.definition.nodes[0].config).toEqual({ script: 'ls' });
    expect(result.current.definition.nodes[0].name).toBe('first');
  });

  it('coalesces consecutive edits to the same node field into one undo step', () => {
    const { result } = setup();
    act(() => {
      result.current.updateNode('n1', { config: { script: 'a' } });
    });
    act(() => {
      result.current.updateNode('n1', { config: { script: 'ab' } });
    });
    act(() => {
      result.current.undo();
    });
    expect(result.current.definition.nodes[0].config).toEqual({ script: 'ls' });
  });

  it('updateMeta rewrites definition-level policy and variables', () => {
    const { result } = setup();
    act(() => {
      result.current.updateMeta({ defaultOnUpstreamFailure: 'abort', variables: { env: { type: 'string' } } });
    });
    expect(result.current.definition.defaultOnUpstreamFailure).toBe('abort');
    expect(result.current.definition.variables).toEqual({ env: { type: 'string' } });
    act(() => {
      result.current.undo();
    });
    expect(result.current.definition.defaultOnUpstreamFailure).toBe('skip');
    expect(result.current.definition.variables).toEqual({});
  });

  it('overwriteDraft saves with onConflict=overwrite (服务端记 WORKFLOW_OVERWRITE)', async () => {
    const { result } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    await act(async () => {
      await result.current.overwriteDraft();
    });
    expect(saveWorkflowDraft.mock.calls[0][1].onConflict).toBe('overwrite');
    expect(saveWorkflowDraft.mock.calls[0][1].baseVersion).toBe(4);
  });

  it('saveDraftAsCopy saves with onConflict=copy (另存副本是新草稿行，不是新工作流)', async () => {
    const { result } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    await act(async () => {
      await result.current.saveDraftAsCopy();
    });
    expect(saveWorkflowDraft.mock.calls[0][1].onConflict).toBe('copy');
  });

  it('自动保存带回的令牌是 baseVersion，不是 version 号', async () => {
    saveWorkflowDraft.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: { id: 10, version: 5, baseVersion: 7 },
    });
    const { result } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    await act(async () => {
      vi.advanceTimersByTime(SAVE_DEBOUNCE_MS);
    });
    // 第一次保存带初始令牌 4
    expect(saveWorkflowDraft.mock.calls[0][1].baseVersion).toBe(4);
    // 保存成功后令牌换成服务端回传的 baseVersion(7)，而不是 version(5)
    act(() => {
      result.current.commit('add-node', nextSnapshot(2));
    });
    await act(async () => {
      vi.advanceTimersByTime(SAVE_DEBOUNCE_MS);
    });
    expect(saveWorkflowDraft.mock.calls[1][1].baseVersion).toBe(7);
  });

  it('reloadFromServer drops local changes and the undo stack', () => {
    const { result } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    act(() => {
      result.current.reloadFromServer(draft);
    });
    expect(result.current.nodes).toHaveLength(1);
    expect(result.current.dirty).toBe(false);
    expect(result.current.canUndo).toBe(false);
  });

  it('does not dirty or enter history when a commit is structurally identical', () => {
    const { result } = setup();
    const snap = result.current.getSnapshot();
    act(() => {
      result.current.commit('select', {
        ...snap,
        nodes: snap.nodes.map((node) => ({ ...node, selected: true })),
      });
    });
    expect(result.current.nodes[0].selected).toBe(true);
    expect(result.current.dirty).toBe(false);
    expect(result.current.canUndo).toBe(false);
    expect(saveWorkflowDraft).not.toHaveBeenCalled();
  });

  it('treats React Flow selection changes as non-dirty / non-history', () => {
    const { result } = setup();
    act(() => {
      result.current.onNodesChange([{ id: 'n1', type: 'select', selected: true }]);
    });
    expect(result.current.nodes[0].selected).toBe(true);
    expect(result.current.dirty).toBe(false);
    expect(result.current.canUndo).toBe(false);
  });

  it('resets snapshot, history and baseVersion when workflowId changes', async () => {
    const onVersionConflict = vi.fn();
    const nextDraft: WorkflowVersionDetail = {
      ...draft,
      id: 11,
      baseVersion: 9,
      definition: {
        ...definition,
        nodes: [
          {
            key: 'n9',
            type: 'script-exec',
            name: 'next workflow',
            position: { x: 10, y: 10 },
            config: { script: 'pwd' },
          },
        ],
      },
    };
    const view = renderHook(
      (props: { workflowId: number; draft: WorkflowVersionDetail }) =>
        useWorkflowEditor({ ...props, onVersionConflict }),
      { initialProps: { workflowId: 7, draft } }
    );

    act(() => {
      view.result.current.commit('add-node', nextSnapshot(1));
    });
    expect(view.result.current.canUndo).toBe(true);
    expect(view.result.current.nodes).toHaveLength(2);

    view.rerender({ workflowId: 8, draft: nextDraft });
    expect(view.result.current.nodes).toHaveLength(1);
    expect(view.result.current.nodes[0].id).toBe('n9');
    expect(view.result.current.definition.nodes[0].key).toBe('n9');
    expect(view.result.current.dirty).toBe(false);
    expect(view.result.current.canUndo).toBe(false);

    // 新工作流的 baseVersion 必须是 9，而不是上一个工作流的 4
    act(() => {
      view.result.current.commit('add-node', nextSnapshot(1));
    });
    await act(async () => {
      await view.result.current.saveNow();
    });
    expect(saveWorkflowDraft.mock.calls[0][1].baseVersion).toBe(9);
  });

  it('keeps dirty for edits made while a save is in flight and schedules a trailing save', async () => {
    let resolveFirst!: (value: unknown) => void;
    saveWorkflowDraft.mockImplementationOnce(
      () => new Promise((resolve) => {
        resolveFirst = resolve;
      })
    );
    const { result } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    await act(async () => {
      vi.advanceTimersByTime(SAVE_DEBOUNCE_MS);
    });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(1);

    // 请求在飞时继续编辑：generation 变了，旧响应不能清 dirty
    act(() => {
      result.current.commit('add-node', nextSnapshot(2));
    });
    await act(async () => {
      resolveFirst({ code: 200, message: 'ok', data: { id: 10, version: 5, baseVersion: 5 } });
    });
    expect(result.current.dirty).toBe(true);
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(1);
    expect(result.current.localDraft?.definition.nodes).toHaveLength(3);

    // trailing save：带服务端回传的新令牌，且包含保存期间的新编辑
    await act(async () => {
      vi.advanceTimersByTime(SAVE_DEBOUNCE_MS);
    });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(2);
    expect(saveWorkflowDraft.mock.calls[1][1].baseVersion).toBe(5);
    expect(saveWorkflowDraft.mock.calls[1][1].definition.nodes).toHaveLength(3);
    expect(result.current.dirty).toBe(false);
  });

  it('saveNow waits for an in-flight save and then retries with the newest definition', async () => {
    let resolveFirst!: (value: unknown) => void;
    saveWorkflowDraft.mockImplementationOnce(
      () => new Promise((resolve) => {
        resolveFirst = resolve;
      })
    );
    const { result } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    await act(async () => {
      vi.advanceTimersByTime(SAVE_DEBOUNCE_MS);
    });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(1);

    act(() => {
      result.current.commit('add-node', nextSnapshot(2));
    });
    let retry!: Promise<void>;
    act(() => {
      retry = result.current.saveNow();
    });
    await act(async () => {
      resolveFirst({ code: 200, message: 'ok', data: { id: 10, version: 5, baseVersion: 5 } });
    });
    await act(async () => {
      await retry;
    });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(2);
    expect(saveWorkflowDraft.mock.calls[1][1].baseVersion).toBe(5);
    expect(saveWorkflowDraft.mock.calls[1][1].definition.nodes).toHaveLength(3);
    expect(result.current.dirty).toBe(false);
  });
});
