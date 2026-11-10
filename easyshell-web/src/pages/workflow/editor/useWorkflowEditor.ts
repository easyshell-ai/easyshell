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
  flowNodeOf,
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
  /** 当前快照（回调里取最新值，避免把 snapshot 塞进 useCallback 依赖） */
  getSnapshot: () => EditorSnapshot;
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

/** 递归按 key 排序后再序列化：同一份定义即使对象 key 顺序不同也判定为 no-op。 */
function sortKeysDeep(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortKeysDeep);
  if (value && typeof value === 'object') {
    const source = value as Record<string, unknown>;
    return Object.fromEntries(Object.keys(source).sort().map((key) => [key, sortKeysDeep(source[key])]));
  }
  return value;
}

export function definitionsEqual(a: WorkflowDefinition, b: WorkflowDefinition): boolean {
  return JSON.stringify(sortKeysDeep(a)) === JSON.stringify(sortKeysDeep(b));
}

/** draft 对象可能每次 render 都新建；用 id + baseVersion 识别"这是另一份服务端草稿" */
function draftKeyOf(workflowId: number, draft: WorkflowVersionDetail): string {
  return `${workflowId}:${draft.id}:${draft.baseVersion}`;
}

export function useWorkflowEditor(options: UseWorkflowEditorOptions): UseWorkflowEditorResult {
  const { workflowId, draft, onVersionConflict, onSaved } = options;
  const { t } = useTranslation();

  const initialSnapshot = useMemo(() => snapshotOf(draft.definition), [draft.definition]);
  /** 首屏也先规范化（condition 边补 sourceHandle / 普通边不写 out），保证 no-op 对比基准一致 */
  const initialDefinition = useMemo(
    () => flowToDefinition(initialSnapshot.nodes, initialSnapshot.edges, draft.definition),
    [initialSnapshot, draft.definition]
  );

  const [snapshot, setSnapshot] = useState<EditorSnapshot>(initialSnapshot);
  /** 只在结构提交时更新：选中/拖拽中的位置变化不重建 definition（保住 callback 依赖身份） */
  const [definition, setDefinition] = useState<WorkflowDefinition>(initialDefinition);
  const [dirty, setDirty] = useState(false);
  /** 每次变更自增：防抖定时器随新变更重置，不依赖回调签名是否变化 */
  const [saveSeq, setSaveSeq] = useState(0);
  const [saving, setSaving] = useState(false);
  const [lastSavedAt, setLastSavedAt] = useState<number | null>(null);
  const [localDraft, setLocalDraft] = useState<StoredDraft | null>(null);

  const historyRef = useRef(new HistoryStack<EditorSnapshot>());
  const snapshotRef = useRef<EditorSnapshot>(initialSnapshot);
  const definitionRef = useRef<WorkflowDefinition>(initialDefinition);
  /** 乐观锁令牌 = 草稿行的 baseVersion（不是 version 号；服务端每次保存 +1） */
  const baseVersionRef = useRef<number>(draft.baseVersion);
  const dirtyRef = useRef(false);
  /** 每次结构编辑 +1；保存成功时只有 generation 未变才允许清 dirty */
  const editGenerationRef = useRef(0);
  /** workflowId 切换 +1：旧工作流的异步保存结果必须丢弃 */
  const workflowEpochRef = useRef(0);
  const saveInFlightRef = useRef<Promise<void> | null>(null);
  const loadedDraftKeyRef = useRef<string>(draftKeyOf(workflowId, draft));
  const previousWorkflowIdRef = useRef(workflowId);
  const [canUndo, setCanUndo] = useState(false);
  const [canRedo, setCanRedo] = useState(false);

  /** 撤销栈深度是 ref 里的可变状态，同步成 state 才能在渲染期安全读取 */
  const syncHistoryFlags = useCallback(() => {
    setCanUndo(historyRef.current.canUndo);
    setCanRedo(historyRef.current.canRedo);
  }, []);

  useEffect(() => {
    const store = storageSafe();
    setLocalDraft(store ? readDraft(store, workflowId) : null);
  }, [workflowId]);

  const markDirty = useCallback(() => {
    editGenerationRef.current += 1;
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
    const nextDefinition = flowToDefinition(next.nodes, next.edges, {
      schemaVersion: 1,
      defaultOnUpstreamFailure: next.meta.defaultOnUpstreamFailure,
      onRejection: next.meta.onRejection,
      variables: next.meta.variables,
      nodes: Object.values(next.configs),
      edges: [],
    });
    snapshotRef.current = next;
    definitionRef.current = nextDefinition;
    setSnapshot(next);
    setDefinition(nextDefinition);
    markDirty();
    syncHistoryFlags();
  }, [markDirty, syncHistoryFlags]);

  const commit = useCallback(
    (label: string, next: EditorSnapshot) => {
      const nextDefinition = flowToDefinition(next.nodes, next.edges, {
        schemaVersion: 1,
        defaultOnUpstreamFailure: next.meta.defaultOnUpstreamFailure,
        onRejection: next.meta.onRejection,
        variables: next.meta.variables,
        nodes: Object.values(next.configs),
        edges: [],
      });
      // 选中态不序列化：结构相同的提交只更新 React Flow 快照，不进历史、不标 dirty
      if (definitionsEqual(nextDefinition, definitionRef.current)) {
        snapshotRef.current = next;
        setSnapshot(next);
        return;
      }
      historyRef.current.push(label, snapshotRef.current, next);
      applySnapshot(next);
    },
    [applySnapshot]
  );

  const onNodesChange = useCallback((changes: NodeChange<WorkflowFlowNode>[]) => {
    // 选中/拖拽中的位置变化不进撤销栈（落一次在 onNodeDragStop 的 commit 里）
    const next = { ...snapshotRef.current, nodes: applyNodeChanges(changes, snapshotRef.current.nodes) };
    snapshotRef.current = next;
    setSnapshot(next);
  }, []);

  const onEdgesChange = useCallback((changes: EdgeChange<WorkflowFlowEdge>[]) => {
    const next = { ...snapshotRef.current, edges: applyEdgeChanges(changes, snapshotRef.current.edges) };
    snapshotRef.current = next;
    setSnapshot(next);
  }, []);

  const updateNode = useCallback(
    (key: string, patch: Partial<WorkflowNode>) => {
      const current = snapshotRef.current.configs[key];
      if (!current) return;
      const updated = { ...current, ...patch };
      // 同步刷新画布节点的 name/摘要（config 改了但 Flow node.data 不重建会显示旧值）
      const flowData = flowNodeOf(updated, snapshotRef.current.meta.variables).data;
      const fields = Object.keys(patch).sort().join(',');
      commit(`config:${key}:${fields}`, {
        ...snapshotRef.current,
        nodes: snapshotRef.current.nodes.map((node) =>
          node.id === key
            ? { ...node, data: { ...node.data, ...flowData, status: node.data.status } }
            : node
        ),
        configs: { ...snapshotRef.current.configs, [key]: updated },
      });
    },
    [commit]
  );

  const updateMeta = useCallback(
    (patch: Partial<WorkflowMeta>, label = 'meta') => {
      const meta = { ...snapshotRef.current.meta, ...patch };
      // 变量变化影响 start 的 inputCount 摘要；一并刷新所有节点 data 的派生字段
      const nodes = snapshotRef.current.nodes.map((node) => {
        const config = snapshotRef.current.configs[node.id];
        if (!config) return node;
        const flowData = flowNodeOf(config, meta.variables).data;
        return { ...node, data: { ...node.data, ...flowData, status: node.data.status } };
      });
      commit(label, { ...snapshotRef.current, meta, nodes });
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

  /**
   * 带冲突策略的保存：reject(默认，409 弹三选一) / overwrite / copy。
   *
   * 请求开始时快照 definition/baseVersion/generation；成功时如果 generation 已经变了，
   * 说明保存期间用户又改了，保留 dirty 并触发一次 trailing save。
   */
  const runSave = useCallback(
    async (onConflict: SaveDraftRequest['onConflict']) => {
      const requestEpoch = workflowEpochRef.current;
      const requestGeneration = editGenerationRef.current;
      const definitionSnapshot = definitionRef.current;
      const baseVersionSnapshot = baseVersionRef.current;
      setSaving(true);
      try {
        const res = await saveWorkflowDraft(workflowId, {
          definition: definitionSnapshot,
          baseVersion: baseVersionSnapshot,
          onConflict,
        });
        // 工作流已切换：旧响应既不能改令牌，也不能清 dirty
        if (requestEpoch !== workflowEpochRef.current) return;

        if (res.code === 200 && res.data) {
          // 服务端把令牌 +1 后回传，下一次保存必须原样带回新值
          baseVersionRef.current = res.data.baseVersion;
          setLastSavedAt(Date.now());
          onSaved?.(res.data);
          if (editGenerationRef.current === requestGeneration) {
            dirtyRef.current = false;
            setDirty(false);
            const store = storageSafe();
            if (store) clearDraft(store, workflowId);
            setLocalDraft(null);
          } else {
            // 保存期间有新编辑：本地留最新草稿，并重置防抖排一次 trailing save
            persistLocalDraft();
            setSaveSeq((seq) => seq + 1);
          }
        } else if (res.code === 409) {
          onVersionConflict({ localBaseVersion: baseVersionSnapshot });
        } else {
          // 网络/服务端失败：改动落地本地，刷新不丢
          persistLocalDraft();
          message.error(res.message || t('workflow.editor.saveFailed'));
        }
      } catch {
        if (requestEpoch !== workflowEpochRef.current) return;
        persistLocalDraft();
        message.error(t('workflow.editor.saveFailedOffline'));
      } finally {
        if (requestEpoch === workflowEpochRef.current) setSaving(false);
      }
    },
    [workflowId, onVersionConflict, onSaved, persistLocalDraft, t]
  );

  /** 同一时刻最多一个请求；后续 saveNow/overwrite 等排队，避免丢令牌或并发覆盖。 */
  const saveWithConflictPolicy = useCallback(
    async (onConflict: SaveDraftRequest['onConflict']) => {
      const pending = saveInFlightRef.current;
      if (pending) {
        try {
          await pending;
        } catch {
          // runSave 内部已处理
        }
      }
      const task = runSave(onConflict);
      saveInFlightRef.current = task;
      try {
        await task;
      } finally {
        if (saveInFlightRef.current === task) saveInFlightRef.current = null;
      }
    },
    [runSave]
  );

  /** 自动/手动保存：若已有请求在飞，等它结束后再按最新 dirty 状态重试一次 */
  const saveNow = useCallback(async () => {
    const pending = saveInFlightRef.current;
    if (pending) await pending;
    if (!dirtyRef.current) return;
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

  /** 整份采纳一份服务端草稿：清历史、清 dirty、换令牌、丢弃旧异步结果。 */
  const adoptDraft = useCallback(
    (next: WorkflowVersionDetail) => {
      const restored = snapshotOf(next.definition);
      const normalized = flowToDefinition(restored.nodes, restored.edges, next.definition);
      workflowEpochRef.current += 1;
      editGenerationRef.current = 0;
      snapshotRef.current = restored;
      definitionRef.current = normalized;
      baseVersionRef.current = next.baseVersion;
      historyRef.current.clear();
      setSnapshot(restored);
      setDefinition(normalized);
      dirtyRef.current = false;
      setDirty(false);
      setSaving(false);
      syncHistoryFlags();
    },
    [syncHistoryFlags]
  );

  // 工作流切换：即使当前快照非空也要重置（旧工作流的节点不能带进新工作流）
  useEffect(() => {
    const key = draftKeyOf(workflowId, draft);
    if (previousWorkflowIdRef.current !== workflowId) {
      previousWorkflowIdRef.current = workflowId;
      loadedDraftKeyRef.current = key;
      adoptDraft(draft);
      setLastSavedAt(null);
      return;
    }
    // 首屏详情异步到达 / 服务端重载：草稿行换了就整份采纳（对象身份不稳定时不误触发）
    if (loadedDraftKeyRef.current !== key) {
      loadedDraftKeyRef.current = key;
      adoptDraft(draft);
    }
  }, [workflowId, draft, adoptDraft]);

  const reloadFromServer = useCallback(
    (next: WorkflowVersionDetail) => {
      loadedDraftKeyRef.current = draftKeyOf(workflowId, next);
      adoptDraft(next);
    },
    [workflowId, adoptDraft]
  );

  const overwriteDraft = useCallback(async () => {
    await saveWithConflictPolicy('overwrite');
  }, [saveWithConflictPolicy]);

  const saveDraftAsCopy = useCallback(async () => {
    await saveWithConflictPolicy('copy');
  }, [saveWithConflictPolicy]);

  const exportDefinition = useCallback(() => definitionRef.current, []);

  const getSnapshot = useCallback(() => snapshotRef.current, []);

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
    getSnapshot,
    saveNow,
    overwriteDraft,
    saveDraftAsCopy,
    reloadFromServer,
    exportDefinition,
    restoreLocalDraft,
    discardLocalDraft,
  };
}
