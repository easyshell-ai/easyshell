import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { applyEdgeChanges, applyNodeChanges, type EdgeChange, type NodeChange } from '@xyflow/react';
import { message } from 'antd';
import { useTranslation } from 'react-i18next';
import {
  saveWorkflowDraft,
  type SaveDraftRequest,
  type WorkflowVersionSummary,
} from '../api';
import type { WorkflowDefinition, WorkflowNode, WorkflowVersionDetail, VariableSpec } from '../types';
import {
  definitionToFlow,
  flowToDefinition,
  type WorkflowFlowEdge,
  type WorkflowFlowNode,
} from '../graph';
import { HistoryStack } from './canvas/HistoryStack';
import { clearDraft, readDraft, writeDraft, type StoredDraft } from '../utils/draftStorage';

/** 结构变更标 dirty 后，无新变更 1.5s 自动 PUT（设计 §4.3） */
export const SAVE_DEBOUNCE_MS = 1500;

export interface WorkflowMeta {
  defaultOnUpstreamFailure: 'abort' | 'skip' | 'partial';
  onRejection: 'abort' | 'continue';
  variables: Record<string, VariableSpec>;
}

/**
 * 编辑器快照 = 撤销栈的原子单位。
 *
 * nodes/edges 是 React Flow 的渲染态（拖拽中每帧都在变），configs 是节点完整记录
 * （type/name/config/重试/超时/策略，画布不渲染它）。两者分开是因为 NF-3 要求拖拽不序列化：
 * 拖拽只碰 nodes.position，落库时才由 flowToDefinition 合成完整 definition。
 */
export interface EditorSnapshot {
  nodes: WorkflowFlowNode[];
  edges: WorkflowFlowEdge[];
  configs: Record<string, WorkflowNode>;
  meta: WorkflowMeta;
}

export interface UseWorkflowEditorOptions {
  workflowId: number;
  draft: WorkflowVersionDetail;
  /** 409 VERSION_CONFLICT：三选一弹窗（覆盖 / 另存副本 / 放弃重载），不做自动合并 */
  onVersionConflict: (info: { localBaseVersion: number }) => void;
  onSaved?: (version: WorkflowVersionSummary) => void;
}

export interface UseWorkflowEditorResult {
  nodes: WorkflowFlowNode[];
  edges: WorkflowFlowEdge[];
  meta: WorkflowMeta;
  definition: WorkflowDefinition;
  dirty: boolean;
  saving: boolean;
  lastSavedAt: number | null;
  canUndo: boolean;
  canRedo: boolean;
  localDraft: StoredDraft | null;
  onNodesChange: (changes: NodeChange<WorkflowFlowNode>[]) => void;
  onEdgesChange: (changes: EdgeChange<WorkflowFlowEdge>[]) => void;
  commit: (label: string, next: EditorSnapshot) => void;
  /** 表单专用：改被选中节点的 config / 元字段（同一 label 的连续编辑会合并成一条撤销） */
  updateNode: (key: string, patch: Partial<WorkflowNode>) => void;
  updateMeta: (patch: Partial<WorkflowMeta>, label?: string) => void;
  undo: () => void;
  redo: () => void;
  saveNow: () => Promise<void>;
  /**
   * 409 三选一①：覆盖保存（onConflict=overwrite，服务端记 WORKFLOW_OVERWRITE 审计）。
   * 覆盖写不校验令牌，但仍旧要把服务端当前令牌带回来，避免下一次保存再撞一次。
   */
  overwriteDraft: () => Promise<void>;
  /** 409 三选一②：另存副本（onConflict=copy，新版本草稿行，不动服务端当前草稿） */
  saveDraftAsCopy: () => Promise<void>;
  /** 409 三选一③：放弃本地变更，重载服务端草稿 */
  reloadFromServer: (draft: WorkflowVersionDetail) => void;
  exportDefinition: () => WorkflowDefinition;
  restoreLocalDraft: () => void;
  discardLocalDraft: () => void;
}

export function metaOf(definition: WorkflowDefinition): WorkflowMeta {
  return {
    defaultOnUpstreamFailure: definition.defaultOnUpstreamFailure || 'skip',
    onRejection: definition.onRejection || 'abort',
    variables: definition.variables || {},
  };
}

export function snapshotOf(definition: WorkflowDefinition): EditorSnapshot {
  const { nodes, edges } = definitionToFlow(definition);
  const configs: Record<string, WorkflowNode> = {};
  for (const node of definition.nodes || []) configs[node.key] = node;
  return { nodes, edges, configs, meta: metaOf(definition) };
}

function storageSafe(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    return null;
  }
}

export function useWorkflowEditor(options: UseWorkflowEditorOptions): UseWorkflowEditorResult {
  const { workflowId, draft, onVersionConflict, onSaved } = options;
  const { t } = useTranslation();

  const initialSnapshot = useMemo(() => snapshotOf(draft.definition), [draft.definition]);

  const [snapshot, setSnapshot] = useState<EditorSnapshot>(initialSnapshot);
  const [dirty, setDirty] = useState(false);
  /** 每次变更自增：防抖定时器随新变更重置，不依赖回调签名是否变化 */
  const [saveSeq, setSaveSeq] = useState(0);
  const [saving, setSaving] = useState(false);
  const [lastSavedAt, setLastSavedAt] = useState<number | null>(null);
  const [localDraft, setLocalDraft] = useState<StoredDraft | null>(null);

  const historyRef = useRef(new HistoryStack<EditorSnapshot>());
  const snapshotRef = useRef<EditorSnapshot>(initialSnapshot);
  const definitionRef = useRef<WorkflowDefinition>(draft.definition);
  /** 乐观锁令牌 = 草稿行的 baseVersion（不是 version 号；服务端每次保存 +1） */
  const baseVersionRef = useRef<number>(draft.baseVersion);
  const dirtyRef = useRef(false);
  const saveInFlightRef = useRef(false);
  const [canUndo, setCanUndo] = useState(false);
  const [canRedo, setCanRedo] = useState(false);

  /** 撤销栈深度是 ref 里的可变状态，同步成 state 才能在渲染期安全读取 */
  const syncHistoryFlags = useCallback(() => {
    setCanUndo(historyRef.current.canUndo);
    setCanRedo(historyRef.current.canRedo);
  }, []);

  useEffect(() => {
    const store = storageSafe();
    if (!store) return;
    const stored = readDraft(store, workflowId);
    if (stored) setLocalDraft(stored);
  }, [workflowId]);

  const definition = useMemo(
    () =>
      flowToDefinition(snapshot.nodes, snapshot.edges, {
        schemaVersion: 1,
        defaultOnUpstreamFailure: snapshot.meta.defaultOnUpstreamFailure,
        onRejection: snapshot.meta.onRejection,
        variables: snapshot.meta.variables,
        nodes: Object.values(snapshot.configs),
        edges: [],
      }),
    [snapshot]
  );

  const markDirty = useCallback(() => {
    dirtyRef.current = true;
    setDirty(true);
    setSaveSeq((seq) => seq + 1);
  }, []);

  const persistLocalDraft = useCallback(() => {
    const store = storageSafe();
    if (!store) return;
    writeDraft(store, workflowId, definitionRef.current, baseVersionRef.current);
    setLocalDraft(readDraft(store, workflowId));
  }, [workflowId]);

  const applySnapshot = useCallback((next: EditorSnapshot) => {
    snapshotRef.current = next;
    definitionRef.current = flowToDefinition(next.nodes, next.edges, {
      schemaVersion: 1,
      defaultOnUpstreamFailure: next.meta.defaultOnUpstreamFailure,
      onRejection: next.meta.onRejection,
      variables: next.meta.variables,
      nodes: Object.values(next.configs),
      edges: [],
    });
    setSnapshot(next);
    markDirty();
    syncHistoryFlags();
  }, [markDirty, syncHistoryFlags]);

  const commit = useCallback(
    (label: string, next: EditorSnapshot) => {
      historyRef.current.push(label, snapshotRef.current, next);
      applySnapshot(next);
    },
    [applySnapshot]
  );

  const onNodesChange = useCallback((changes: NodeChange<WorkflowFlowNode>[]) => {
    // 选中/拖拽中的位置变化不进撤销栈（落一次在 onNodeDragStop 的 commit 里）
    setSnapshot((current) => ({ ...current, nodes: applyNodeChanges(changes, current.nodes) }));
  }, []);

  const onEdgesChange = useCallback((changes: EdgeChange<WorkflowFlowEdge>[]) => {
    setSnapshot((current) => ({ ...current, edges: applyEdgeChanges(changes, current.edges) }));
  }, []);

  const updateNode = useCallback(
    (key: string, patch: Partial<WorkflowNode>) => {
      const current = snapshotRef.current.configs[key];
      if (!current) return;
      const fields = Object.keys(patch).sort().join(',');
      commit(`config:${key}:${fields}`, {
        ...snapshotRef.current,
        configs: { ...snapshotRef.current.configs, [key]: { ...current, ...patch } },
      });
    },
    [commit]
  );

  const updateMeta = useCallback(
    (patch: Partial<WorkflowMeta>, label = 'meta') => {
      commit(label, { ...snapshotRef.current, meta: { ...snapshotRef.current.meta, ...patch } });
    },
    [commit]
  );

  const undo = useCallback(() => {
    const previous = historyRef.current.undo();
    if (!previous) {
      message.info(t('workflow.editor.nothingToUndo'));
      return;
    }
    applySnapshot(previous);
  }, [applySnapshot, t]);

  /** 带冲突策略的保存：reject(默认，409 弹三选一) / overwrite / copy */
  const saveWithConflictPolicy = useCallback(async (onConflict: SaveDraftRequest['onConflict']) => {
    if (saveInFlightRef.current) return;
    saveInFlightRef.current = true;
    setSaving(true);
    try {
      const res = await saveWorkflowDraft(workflowId, {
        definition: definitionRef.current,
        baseVersion: baseVersionRef.current,
        onConflict,
      });
      if (res.code === 200 && res.data) {
        // 服务端把令牌 +1 后回传，下一次保存必须原样带回新值
        baseVersionRef.current = res.data.baseVersion;
        dirtyRef.current = false;
        setDirty(false);
        setLastSavedAt(Date.now());
        const store = storageSafe();
        if (store) clearDraft(store, workflowId);
        setLocalDraft(null);
        onSaved?.(res.data);
      } else if (res.code === 409) {
        onVersionConflict({ localBaseVersion: baseVersionRef.current });
      } else {
        // 网络/服务端失败：改动落地本地，刷新不丢
        persistLocalDraft();
        message.error(res.message || t('workflow.editor.saveFailed'));
      }
    } catch {
      persistLocalDraft();
      message.error(t('workflow.editor.saveFailedOffline'));
    } finally {
      saveInFlightRef.current = false;
      setSaving(false);
    }
  }, [workflowId, onVersionConflict, onSaved, persistLocalDraft, t]);

  /** 自动/手动保存（默认 reject：409 交给三选一弹窗） */
  const saveNow = useCallback(async () => {
    if (!dirtyRef.current || saveInFlightRef.current) return;
    await saveWithConflictPolicy('reject');
  }, [saveWithConflictPolicy]);

  const redo = useCallback(() => {
    const next = historyRef.current.redo();
    if (!next) {
      message.info(t('workflow.editor.nothingToRedo'));
      return;
    }
    applySnapshot(next);
  }, [applySnapshot, t]);

  // 1.5s 无新变更自动 PUT（saveSeq 每次变更都变，定时器因此被重置）
  useEffect(() => {
    if (!dirty) return;
    const timer = window.setTimeout(() => {
      void saveNow();
    }, SAVE_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [dirty, saveSeq, saveNow]);

  // beforeunload 提示
  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => {
      if (!dirtyRef.current) return;
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, []);

  const overwriteDraft = useCallback(async () => {
    await saveWithConflictPolicy('overwrite');
  }, [saveWithConflictPolicy]);

  const saveDraftAsCopy = useCallback(async () => {
    await saveWithConflictPolicy('copy');
  }, [saveWithConflictPolicy]);

  const reloadFromServer = useCallback((next: WorkflowVersionDetail) => {
    const restored = snapshotOf(next.definition);
    snapshotRef.current = restored;
    definitionRef.current = next.definition;
    baseVersionRef.current = next.baseVersion;
    historyRef.current.clear();
    setSnapshot(restored);
    dirtyRef.current = false;
    setDirty(false);
    syncHistoryFlags();
  }, [syncHistoryFlags]);

  // 草稿晚于挂载到达（详情接口异步返回）：首屏快照是空的，拿到真草稿后整份采纳
  useEffect(() => {
    if (draft.definition.nodes.length > 0 && snapshotRef.current.nodes.length === 0) {
      reloadFromServer(draft);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft]);

  const exportDefinition = useCallback(() => definitionRef.current, []);

  const restoreLocalDraft = useCallback(() => {
    const store = storageSafe();
    if (!store) return;
    const stored = readDraft(store, workflowId);
    if (!stored) return;
    applySnapshot(snapshotOf(stored.definition));
    setLocalDraft(null);
    message.success(t('workflow.editor.localDraftRestored'));
  }, [workflowId, applySnapshot, t]);

  const discardLocalDraft = useCallback(() => {
    const store = storageSafe();
    if (store) clearDraft(store, workflowId);
    setLocalDraft(null);
  }, [workflowId]);

  return {
    nodes: snapshot.nodes,
    edges: snapshot.edges,
    meta: snapshot.meta,
    definition,
    dirty,
    saving,
    lastSavedAt,
    canUndo,
    canRedo,
    localDraft,
    onNodesChange,
    onEdgesChange,
    commit,
    updateNode,
    updateMeta,
    undo,
    redo,
    saveNow,
    overwriteDraft,
    saveDraftAsCopy,
    reloadFromServer,
    exportDefinition,
    restoreLocalDraft,
    discardLocalDraft,
  };
}
