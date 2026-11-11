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
  definitionSha256: 'sha-4',
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
    saveWorkflowDraft.mockResolvedValue({ code: 200, message: 'ok', data: { id: 10, version: 5, baseVersion: 5, definitionSha256: 'sha-5' } });
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

  it('sends explicit absence and base zero for a new draft', async () => {
    const { result } = renderHook(() => useWorkflowEditor({ workflowId: 7, draft: { ...draft, id: 0 }, onVersionConflict: vi.fn() }));
    act(() => result.current.commit('edit', nextSnapshot(1)));
    await act(async () => { await result.current.saveNow(); });
    expect(saveWorkflowDraft).toHaveBeenCalledWith(7, expect.objectContaining({ expectedAbsent: true, baseVersion: 0 }));
  });

  it.each(['valid', 'corrupt', 'empty'])('never accesses an unowned %s legacy draft across editor operations', async (kind) => {
    const raw = kind === 'valid' ? JSON.stringify({ definition, baseVersion: 4, savedAt: 1 }) : kind === 'corrupt' ? '{broken' : '';
    window.localStorage.setItem('wf-draft-7', raw);
    const get = vi.spyOn(Storage.prototype, 'getItem');
    const set = vi.spyOn(Storage.prototype, 'setItem');
    const remove = vi.spyOn(Storage.prototype, 'removeItem');
    const view = renderHook((props: { workflowId: number; draft: WorkflowVersionDetail }) => useWorkflowEditor({ ...props, onVersionConflict: vi.fn() }), { initialProps: { workflowId: 7, draft } });
    expect(view.result.current.localDraft).toBeNull();
    act(() => { view.result.current.restoreLocalDraft(); view.result.current.discardLocalDraft(); view.result.current.commit('edit', nextSnapshot(1)); });
    saveWorkflowDraft.mockRejectedValueOnce(new Error('offline'));
    await act(async () => { expect(await view.result.current.saveNow()).toBe(false); });
    expect(view.result.current.dirty).toBe(true);
    view.rerender({ workflowId: 8, draft: { ...draft, id: 20 } });
    view.rerender({ workflowId: 7, draft });
    expect(view.result.current.definition.nodes).toHaveLength(2);
    await act(async () => { expect(await view.result.current.saveNow()).toBe(true); });
    let checkpoint!: NonNullable<Awaited<ReturnType<typeof view.result.current.beginDiscard>>>;
    await act(async () => { checkpoint = (await view.result.current.beginDiscard())!; });
    act(() => { expect(view.result.current.discardAndReload(draft, checkpoint)).toBe(true); });
    expect(get).not.toHaveBeenCalled();
    expect(set).not.toHaveBeenCalled();
    expect(remove).not.toHaveBeenCalled();
    get.mockRestore(); set.mockRestore(); remove.mockRestore();
    expect(window.localStorage.getItem('wf-draft-7')).toBe(raw);
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
    expect(onSaved).toHaveBeenCalledWith({ id: 10, version: 5, baseVersion: 5, definitionSha256: 'sha-5' });
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
    expect(onVersionConflict).toHaveBeenCalledWith({ localBaseVersion: 4, target: null });
    expect(result.current.dirty).toBe(true);
  });

  it('keeps only an in-memory draft when the save fails', async () => {
    saveWorkflowDraft.mockRejectedValue(new Error('offline'));
    const { result } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    await act(async () => {
      vi.advanceTimersByTime(SAVE_DEBOUNCE_MS);
    });
    expect(window.localStorage.getItem('wf-draft-7')).toBeNull();
    expect(result.current.localDraft).toBeNull();
    expect(result.current.dirty).toBe(true);
    expect(result.current.definition.nodes).toHaveLength(2);
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

  it('refuses overwrite without a displayed target token', async () => {
    const { result } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    await act(async () => {
      await result.current.overwriteDraft({ draftId: 10, baseVersion: 4, definitionSha256: 'sha-4' });
    });
    expect(saveWorkflowDraft).not.toHaveBeenCalled();
  });

  it('binds overwrite to the displayed conflict target and requires renewed confirmation after another 409', async () => {
    const target = { draftId: 21, baseVersion: 8, definitionSha256: 'target-hash' };
    const changed = { draftId: 22, baseVersion: 9, definitionSha256: 'changed-hash' };
    saveWorkflowDraft.mockResolvedValueOnce({ code: 409, message: 'conflict', data: { draftVersionId: 21, currentBaseVersion: 8, currentDefinitionSha256: 'target-hash' } });
    saveWorkflowDraft.mockResolvedValueOnce({ code: 409, message: 'conflict', data: { draftVersionId: 22, currentBaseVersion: 9, currentDefinitionSha256: 'changed-hash' } });
    const { result, onVersionConflict } = setup();
    act(() => result.current.commit('edit', nextSnapshot(1)));
    await act(async () => { await result.current.saveNow(); });
    expect(onVersionConflict).toHaveBeenLastCalledWith({ localBaseVersion: 4, target });
    await act(async () => { await result.current.overwriteDraft(target); });
    expect(saveWorkflowDraft.mock.calls[1][1]).toMatchObject({ ...target, onConflict: 'overwrite' });
    expect(onVersionConflict).toHaveBeenLastCalledWith({ localBaseVersion: 4, target: changed });
    await act(async () => { await result.current.overwriteDraft(target); });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(2);
    expect(result.current.dirty).toBe(true);
    expect(window.localStorage.getItem('wf-draft-7')).toBeNull();
    await act(async () => { await result.current.overwriteDraft(changed); });
    expect(saveWorkflowDraft.mock.calls[2][1]).toMatchObject({ ...changed, onConflict: 'overwrite' });
  });

  it('saveDraftAsCopy saves with onConflict=copy (另存副本是新草稿行，不是新工作流)', async () => {
    const { result } = setup();
    act(() => {
      result.current.commit('add-node', nextSnapshot(1));
    });
    await act(async () => {
      await result.current.saveDraftAsCopy();
    });
    expect(saveWorkflowDraft.mock.calls[0][1]).toMatchObject({ onConflict: 'copy', draftId: 10, baseVersion: 4, definitionSha256: 'sha-4' });
  });

  it('自动保存带回的令牌是 baseVersion，不是 version 号', async () => {
    saveWorkflowDraft.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: { id: 10, version: 5, baseVersion: 7, definitionSha256: 'sha-7' },
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
      resolveFirst({ code: 200, message: 'ok', data: { id: 10, version: 5, baseVersion: 5, definitionSha256: 'sha-5' } });
    });
    expect(result.current.dirty).toBe(true);
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(1);
    expect(result.current.definition.nodes).toHaveLength(3);

    // trailing save：带服务端回传的新令牌，且包含保存期间的新编辑
    await act(async () => {
      vi.advanceTimersByTime(SAVE_DEBOUNCE_MS);
    });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(2);
    expect(saveWorkflowDraft.mock.calls[1][1].baseVersion).toBe(5);
    expect(saveWorkflowDraft.mock.calls[1][1].definition.nodes).toHaveLength(3);
    expect(result.current.dirty).toBe(false);
  });

  it('saveNow refuses an in-flight save whose generation changed', async () => {
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
    let retry!: Promise<boolean>;
    act(() => {
      retry = result.current.saveNow();
    });
    await act(async () => {
      resolveFirst({ code: 200, message: 'ok', data: { id: 10, version: 5, baseVersion: 5, definitionSha256: 'sha-5' } });
    });
    await act(async () => {
      expect(await retry).toBe(false);
    });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(1);
    expect(result.current.dirty).toBe(true);
  });

  it.each(['conflict', 'server', 'network'])('saveNow reports %s failure and preserves dirty', async (failure) => {
    if (failure === 'network') saveWorkflowDraft.mockRejectedValue(new Error('offline'));
    else saveWorkflowDraft.mockResolvedValue({ code: failure === 'conflict' ? 409 : 500, message: 'error' });
    const { result } = setup();
    act(() => result.current.commit('edit', nextSnapshot(1)));
    await act(async () => {
      expect(await result.current.saveNow()).toBe(false);
    });
    expect(result.current.dirty).toBe(true);
    expect(window.localStorage.getItem('wf-draft-7')).toBeNull();
  });

  it('saveNow returns true only after the current generation is saved', async () => {
    const { result } = setup();
    act(() => result.current.commit('edit', nextSnapshot(1)));
    await act(async () => {
      expect(await result.current.saveNow()).toBe(true);
    });
    expect(result.current.dirty).toBe(false);
  });

  it('serializes multiple queued policies and coalesces duplicate copies', async () => {
    let resolveFirst!: (value: unknown) => void;
    let resolveCopy!: (value: unknown) => void;
    saveWorkflowDraft.mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }));
    saveWorkflowDraft.mockImplementationOnce(() => new Promise((resolve) => { resolveCopy = resolve; }));
    const { result } = setup();
    act(() => result.current.commit('edit', nextSnapshot(1)));
    let first!: Promise<boolean>, copy!: Promise<boolean>, duplicate!: Promise<boolean>;
    act(() => { first = result.current.saveNow(); copy = result.current.saveDraftAsCopy(); duplicate = result.current.saveDraftAsCopy(); });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(1);
    await act(async () => { resolveFirst({ code: 200, data: { id: 10, baseVersion: 5, definitionSha256: 'sha-5' } }); await first; });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(2);
    expect(saveWorkflowDraft.mock.calls[1][1]).toMatchObject({ onConflict: 'copy', draftId: 10, baseVersion: 5, definitionSha256: 'sha-5' });
    await act(async () => { resolveCopy({ code: 200, data: { id: 11, baseVersion: 0, definitionSha256: 'copy' } }); await Promise.all([copy, duplicate]); });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(2);
  });

  it('does not execute queued operations for a workflow after switching', async () => {
    let resolveFirst!: (value: unknown) => void;
    saveWorkflowDraft.mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }));
    const view = renderHook((props: { workflowId: number; draft: WorkflowVersionDetail }) => useWorkflowEditor({ ...props, onVersionConflict: vi.fn() }), { initialProps: { workflowId: 7, draft } });
    act(() => view.result.current.commit('edit', nextSnapshot(1)));
    let first!: Promise<boolean>, queued!: Promise<boolean>;
    act(() => { first = view.result.current.saveNow(); queued = view.result.current.saveDraftAsCopy(); });
    view.rerender({ workflowId: 8, draft: { ...draft, id: 20 } });
    await act(async () => { resolveFirst({ code: 200, data: { id: 10, baseVersion: 5, definitionSha256: 'sha-5' } }); expect(await first).toBe(false); expect(await queued).toBe(false); });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(1);
  });

  it('preserves legacy data on confirmed discard and rejects a stale checkpoint', async () => {
    window.localStorage.setItem('wf-draft-7', JSON.stringify({ definition, baseVersion: 4, savedAt: 1 }));
    const { result } = setup();
    let checkpoint!: NonNullable<Awaited<ReturnType<typeof result.current.beginDiscard>>>;
    await act(async () => { checkpoint = (await result.current.beginDiscard())!; });
    act(() => result.current.commit('edit', nextSnapshot(1)));
    act(() => { expect(result.current.discardAndReload(draft, checkpoint)).toBe(false); });
    expect(result.current.dirty).toBe(true);
    expect(window.localStorage.getItem('wf-draft-7')).toBeTruthy();
    await act(async () => { checkpoint = (await result.current.beginDiscard())!; });
    act(() => { expect(result.current.discardAndReload(draft, checkpoint)).toBe(true); });
    expect(result.current.dirty).toBe(false);
    expect(result.current.localDraft).toBeNull();
    expect(window.localStorage.getItem('wf-draft-7')).toBeTruthy();
  });

  it('freezes saves, invalidates queued requests and waits for the active PUT before GET', async () => {
    let resolvePut!: (value: unknown) => void;
    saveWorkflowDraft.mockImplementationOnce(() => new Promise((resolve) => { resolvePut = resolve; }));
    const { result } = setup();
    act(() => result.current.commit('edit', nextSnapshot(1)));
    let first!: Promise<boolean>, queued!: Promise<boolean>, barrier!: ReturnType<typeof result.current.beginDiscard>;
    act(() => { first = result.current.saveNow(); queued = result.current.saveDraftAsCopy(); barrier = result.current.beginDiscard(); });
    let ready = false;
    void barrier.then(() => { ready = true; });
    await act(async () => { await Promise.resolve(); });
    expect(ready).toBe(false);
    await act(async () => { expect(await result.current.saveDraftAsCopy()).toBe(false); });
    await act(async () => { resolvePut({ code: 200, data: { id: 10, baseVersion: 5, definitionSha256: 'sha-5' } }); await first; expect(await queued).toBe(false); });
    const checkpoint = await barrier;
    expect(checkpoint).not.toBeNull();
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(1);
    expect(window.localStorage.getItem('wf-draft-7')).toBeNull();
    act(() => result.current.endDiscard(checkpoint!));
    expect(result.current.dirty).toBe(true);
  });

  it('rejects discard if a node moves during GET and unfreezes saving', async () => {
    const { result } = setup();
    act(() => result.current.commit('edit', nextSnapshot(1)));
    let checkpoint!: NonNullable<Awaited<ReturnType<typeof result.current.beginDiscard>>>;
    await act(async () => { checkpoint = (await result.current.beginDiscard())!; });
    act(() => result.current.onNodesChange([{ id: 'n1', type: 'position', position: { x: 20, y: 30 }, dragging: true }]));
    act(() => { expect(result.current.discardAndReload(draft, checkpoint)).toBe(false); });
    expect(result.current.nodes[0].position).toEqual({ x: 20, y: 30 });
    await act(async () => { await result.current.saveNow(); });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(1);
  });

  it('allows new workflow saves while the old PUT remains unresolved', async () => {
    saveWorkflowDraft.mockImplementationOnce(() => new Promise(() => {}));
    const view = renderHook((props: { workflowId: number; draft: WorkflowVersionDetail }) => useWorkflowEditor({ ...props, onVersionConflict: vi.fn() }), { initialProps: { workflowId: 7, draft } });
    act(() => { view.result.current.commit('edit', nextSnapshot(1)); void view.result.current.saveNow(); });
    view.rerender({ workflowId: 8, draft: { ...draft, id: 20 } });
    act(() => view.result.current.commit('edit', nextSnapshot(1)));
    await act(async () => { await view.result.current.saveNow(); });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(2);
    expect(saveWorkflowDraft.mock.calls[1][0]).toBe(8);
  });

  it('preserves edits after an in-flight PUT when switching workflows', async () => {
    let resolvePut!: (value: unknown) => void;
    saveWorkflowDraft.mockImplementationOnce(() => new Promise((resolve) => { resolvePut = resolve; }));
    const view = renderHook((props: { workflowId: number; draft: WorkflowVersionDetail }) => useWorkflowEditor({ ...props, onVersionConflict: vi.fn() }), { initialProps: { workflowId: 7, draft } });
    act(() => { view.result.current.commit('edit', nextSnapshot(1)); void view.result.current.saveNow(); });
    act(() => view.result.current.commit('edit', nextSnapshot(2)));
    view.rerender({ workflowId: 8, draft: { ...draft, id: 20 } });
    expect(window.localStorage.getItem('wf-draft-7')).toBeNull();
    await act(async () => { resolvePut({ code: 200, data: { id: 10, baseVersion: 5, definitionSha256: 'sha-5' } }); });
    expect(window.localStorage.getItem('wf-draft-7')).toBeNull();
    view.rerender({ workflowId: 7, draft });
    expect(view.result.current.definition.nodes).toHaveLength(3);
    expect(view.result.current.dirty).toBe(true);
  });

  it('retains edits in memory when switch persistence fails', () => {
    const view = renderHook((props: { workflowId: number; draft: WorkflowVersionDetail }) => useWorkflowEditor({ ...props, onVersionConflict: vi.fn() }), { initialProps: { workflowId: 7, draft } });
    act(() => view.result.current.commit('edit', nextSnapshot(2)));
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    view.rerender({ workflowId: 8, draft: { ...draft, id: 20 } });
    expect(view.result.current.hasUnsavedMemoryDrafts()).toBe(true);
    view.rerender({ workflowId: 7, draft });
    expect(view.result.current.definition.nodes).toHaveLength(3);
    expect(view.result.current.dirty).toBe(true);
    spy.mockRestore();
  });

  it('restarts dirty autosave after ending a discard barrier', async () => {
    const { result } = setup();
    act(() => result.current.commit('edit', nextSnapshot(1)));
    let checkpoint!: NonNullable<Awaited<ReturnType<typeof result.current.beginDiscard>>>;
    await act(async () => { checkpoint = (await result.current.beginDiscard())!; vi.advanceTimersByTime(SAVE_DEBOUNCE_MS); });
    expect(saveWorkflowDraft).not.toHaveBeenCalled();
    act(() => result.current.endDiscard(checkpoint));
    await act(async () => { vi.advanceTimersByTime(SAVE_DEBOUNCE_MS); });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(1);
  });

  it('keeps dirty content and unlocks when storage throws during an active save', async () => {
    let resolvePut!: (value: unknown) => void;
    saveWorkflowDraft.mockImplementationOnce(() => new Promise((resolve) => { resolvePut = resolve; }));
    const { result } = setup();
    act(() => result.current.commit('edit', nextSnapshot(1)));
    let save!: Promise<boolean>, barrier!: ReturnType<typeof result.current.beginDiscard>;
    act(() => { save = result.current.saveNow(); barrier = result.current.beginDiscard(); });
    const storageSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    await act(async () => { resolvePut({ code: 500, message: 'failed' }); await save; });
    const checkpoint = await barrier;
    expect(checkpoint).not.toBeNull();
    act(() => result.current.endDiscard(checkpoint!));
    storageSpy.mockRestore();
    expect(result.current.dirty).toBe(true);
    await act(async () => { vi.advanceTimersByTime(SAVE_DEBOUNCE_MS); });
    expect(saveWorkflowDraft).toHaveBeenCalledTimes(2);
  });
});
