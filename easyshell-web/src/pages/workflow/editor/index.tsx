import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Col,
  Drawer,
  Modal,
  Row,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  AppstoreOutlined,
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloudUploadOutlined,
  DeploymentUnitOutlined,
  FileTextOutlined,
  PlayCircleOutlined,
  QuestionCircleOutlined,
  RedoOutlined,
  SaveOutlined,
  UndoOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { theme } from 'antd';
import {
  getWorkflowDetail,
  getWorkflowVersion,
  issuesOf,
  publishWorkflow,
  triggerWorkflowRun,
  validateWorkflow,
} from '../api';
import type {
  ValidationIssue,
  WorkflowDefinition,
  WorkflowDetail,
  WorkflowNode,
  WorkflowNodeType,
  WorkflowVersionDetail,
  VariableSpec,
} from '../types';
import {
  autoLayoutPositions,
  checkConnection,
  computeDeleteImpact,
  createWorkflowNode,
  edgeIdFor,
  ensureEntryExit,
  flowNodeOf,
  nextNodeKey,
  type WorkflowFlowEdge,
  type WorkflowFlowNode,
} from '../graph';
import WorkflowCanvas from './canvas/WorkflowCanvas';
import NodePalette from './NodePalette';
import DefinitionJsonView from './DefinitionJsonView';
import { EdgeEditModal, PublishModal, TriggerModal, VersionConflictModal } from './modals';
import { snapshotOf, useWorkflowEditor } from './useWorkflowEditor';
import { buildClipboardPayload, writeClipboard } from './canvas/clipboard';
import { NodeInspector } from './inspector/NodeInspector';
import { HelpDrawer } from './inspector/HelpDrawer';
import { TemplateGallery } from './templates/TemplateGallery';
import type { WorkflowTemplateId } from './templates';
import '../workflow.css';

const { Title, Text } = Typography;

const EMPTY_DEFINITION: WorkflowDefinition = {
  schemaVersion: 1,
  defaultOnUpstreamFailure: 'skip',
  onRejection: 'abort',
  variables: {},
  nodes: [],
  edges: [],
};

const EMPTY_DRAFT: WorkflowVersionDetail = {
  id: 0,
  version: 0,
  baseVersion: 0,
  isValid: false,
  createdAt: '',
  definition: EMPTY_DEFINITION,
};

const FIXED_TYPES: WorkflowNodeType[] = ['start', 'end'];

function isFixedNode(node: WorkflowNode): boolean {
  return FIXED_TYPES.includes(node.type);
}

/** 窄屏（<1200）检查器走 Drawer；窗口尺寸变化实时切换 */
function useNarrowLayout(): boolean {
  const [narrow, setNarrow] = useState(() => typeof window !== 'undefined' && window.innerWidth < 1200);
  useEffect(() => {
    const onChange = () => setNarrow(window.innerWidth < 1200);
    window.addEventListener('resize', onChange);
    return () => window.removeEventListener('resize', onChange);
  }, []);
  return narrow;
}

const WorkflowEditorPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const workflowId = Number(id);
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const narrow = useNarrowLayout();

  const [detail, setDetail] = useState<WorkflowDetail | null>(null);
  const [draft, setDraft] = useState<WorkflowVersionDetail>(EMPTY_DRAFT);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<{ nodeKeys: string[]; edgeIds: string[] } | null>(null);
  const [editingEdgeId, setEditingEdgeId] = useState<string | null>(null);
  const [publishOpen, setPublishOpen] = useState(false);
  const [publishIssues, setPublishIssues] = useState<ValidationIssue[]>([]);
  const [publishLoading, setPublishLoading] = useState(false);
  const [triggerOpen, setTriggerOpen] = useState(false);
  const [triggerLoading, setTriggerLoading] = useState(false);
  const [conflictOpen, setConflictOpen] = useState(false);
  const [conflictBusy, setConflictBusy] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [templatesOpen, setTemplatesOpen] = useState(false);
  const [validateOpen, setValidateOpen] = useState(false);
  const [validateIssues, setValidateIssues] = useState<ValidationIssue[]>([]);
  const [validateLoading, setValidateLoading] = useState(false);

  const loadDetail = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const res = await getWorkflowDetail(workflowId);
      if (res.code === 200 && res.data) {
        setDetail(res.data);
        if (res.data.draft) {
          setDraft(res.data.draft);
        } else if (res.data.workflow.currentVersionId) {
          // 已发布但没有草稿：先展示当前发布版本，用户下一次保存会由服务端新建草稿行。
          const versionRes = await getWorkflowVersion(workflowId, res.data.workflow.currentVersionId);
          if (versionRes.code === 200 && versionRes.data) {
            setDraft({
              ...versionRes.data,
              // id 必须是"非草稿"的哨兵，否则与 EMPTY_DRAFT 的 0:0 key 相同，
              // useWorkflowEditor 不会采纳这份已发布定义。
              id: -1,
              version: 0,
              baseVersion: 0,
              isValid: true,
            });
          } else {
            setDraft(EMPTY_DRAFT);
          }
        } else {
          setDraft(EMPTY_DRAFT);
        }
      } else if (res.code === 404) {
        setLoadError('404');
      } else if (res.code === 403) {
        setLoadError('403');
      } else {
        setLoadError(res.message || 'error');
      }
    } catch {
      setLoadError('error');
    } finally {
      setLoading(false);
    }
  }, [workflowId]);

  useEffect(() => {
    if (!Number.isFinite(workflowId) || workflowId <= 0) {
      setLoadError('404');
      setLoading(false);
      return;
    }
    void loadDetail();
  }, [workflowId, loadDetail]);

  const editor = useWorkflowEditor({
    workflowId,
    draft,
    onVersionConflict: () => setConflictOpen(true),
  });

  // 解构出稳定引用：回调依赖只列真正用到的函数/值，不把整个 editor 对象塞进 deps
  const {
    definition,
    commit,
    updateNode,
    updateMeta,
    saveNow,
    undo,
    redo,
    overwriteDraft,
    saveDraftAsCopy,
    reloadFromServer,
    getSnapshot,
    exportDefinition,
  } = editor;

  // 连接校验只需要节点类型/边结构；definition 只在结构提交时换身份
  const connectionContext = useMemo(
    () => ({ nodes: definition.nodes, edges: definition.edges }),
    [definition]
  );

  const selectedNodeKeys = useMemo(() => editor.nodes.filter((node) => node.selected).map((node) => node.id), [editor.nodes]);
  const selectedEdgeCount = useMemo(() => editor.edges.filter((edge) => edge.selected).length, [editor.edges]);
  const activeNodeKey = selectedNodeKeys.length === 1 ? selectedNodeKeys[0] : null;
  const activeNode = useMemo(
    () => definition.nodes.find((node) => node.key === activeNodeKey) || null,
    [definition.nodes, activeNodeKey]
  );

  const selectNode = useCallback(
    (key: string) => {
      editor.onNodesChange(
        editor.nodes.map((node) => ({ id: node.id, type: 'select' as const, selected: node.id === key }))
      );
    },
    [editor.nodes, editor.onNodesChange]
  );

  /** Escape 关闭检查器：焦点在 Data/Docs Tab 或表单里时，画布的键盘处理器收不到事件。 */
  const clearSelection = useCallback(() => {
    editor.onNodesChange(
      editor.nodes.map((node) => ({ id: node.id, type: 'select' as const, selected: false }))
    );
  }, [editor.nodes, editor.onNodesChange]);

  useEffect(() => {
    const modalOpen =
      helpOpen || templatesOpen || publishOpen || triggerOpen || conflictOpen || validateOpen ||
      !!editingEdgeId || !!pendingDelete;
    if (modalOpen) return;
    const handler = (event: KeyboardEvent) => {
      if (event.key === 'Escape') clearSelection();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [
    clearSelection, helpOpen, templatesOpen, publishOpen, triggerOpen, conflictOpen, validateOpen,
    editingEdgeId, pendingDelete,
  ]);

  /* ── 结构变更：全部经 commit → 进撤销栈 + 标 dirty + 防抖保存 ───── */

  const handleConnect = useCallback(
    (connection: { source: string; target: string; sourceHandle?: string | null }) => {
      const rejection = checkConnection(connectionContext, connection);
      if (rejection) {
        message.warning(t(`workflow.canvas.reject.${rejection}`));
        return;
      }
      const edgeId = edgeIdFor(connection.source, connection.sourceHandle, connection.target);
      const sourceType = connectionContext.nodes.find((node) => node.key === connection.source)?.type;
      const newEdge: WorkflowFlowEdge = {
        id: edgeId,
        source: connection.source,
        target: connection.target,
        sourceHandle: connection.sourceHandle,
        type: 'workflowEdge',
        data: { sourceType },
        selected: true,
      };
      const current = getSnapshot();
      commit(`connect:${edgeId}`, {
        ...current,
        // 连线成功后自动选中新边（选中态仍在 React Flow 对象上，不进 definition）
        edges: [
          ...current.edges.map((edge) => (edge.selected ? { ...edge, selected: false } : edge)),
          newEdge,
        ],
      });
    },
    [connectionContext, commit, getSnapshot, t]
  );

  const handleDropNode = useCallback(
    (type: WorkflowNodeType, position: { x: number; y: number }) => {
      if (FIXED_TYPES.includes(type)) {
        message.info(t('workflow.canvas.fixedNodeOnly'));
        return;
      }
      const key = nextNodeKey(definition.nodes.map((n) => n.key));
      const node = createWorkflowNode(type, key, position, t(`workflow.node.type.${type}`));
      // 选中态只存在 React Flow 节点上（selected: true），不写 definition
      const newNode: WorkflowFlowNode = { ...flowNodeOf(node, definition.variables), selected: true };
      const current = getSnapshot();
      commit(`add:${key}`, {
        ...current,
        nodes: [...current.nodes.map((n) => (n.selected ? { ...n, selected: false } : n)), newNode],
        configs: { ...current.configs, [key]: node },
      });
    },
    [commit, definition.nodes, definition.variables, getSnapshot, t]
  );

  const handleMoveNodes = useCallback(
    (moves: Array<{ id: string; position: { x: number; y: number } }>) => {
      const positionById = new Map(moves.map((move) => [move.id, move.position]));
      const current = getSnapshot();
      commit('move', {
        ...current,
        nodes: current.nodes.map((node) => {
          const position = positionById.get(node.id);
          return position ? { ...node, position } : node;
        }),
        configs: Object.fromEntries(
          Object.entries(current.configs).map(([key, node]) => [
            key,
            positionById.has(key) ? { ...node, position: positionById.get(key)! } : node,
          ])
        ),
      });
    },
    [commit, getSnapshot]
  );

  const handleRequestDelete = useCallback(
    (nodeKeys: string[], edgeIds: string[]) => {
      if (nodeKeys.length === 0 && edgeIds.length === 0) return;
      const fixedKeys = new Set(definition.nodes.filter(isFixedNode).map((node) => node.key));
      const deletableKeys = nodeKeys.filter((key) => !fixedKeys.has(key));
      if (deletableKeys.length === 0 && edgeIds.length === 0) {
        message.info(t('workflow.inspector.deleteDisabled'));
        return;
      }
      setPendingDelete({ nodeKeys: deletableKeys, edgeIds });
    },
    [definition.nodes, t]
  );

  const confirmDelete = useCallback(() => {
    if (!pendingDelete) return;
    const fixedKeys = new Set(definition.nodes.filter(isFixedNode).map((node) => node.key));
    const nodeSet = new Set(pendingDelete.nodeKeys.filter((key) => !fixedKeys.has(key)));
    const edgeSet = new Set(pendingDelete.edgeIds);
    const current = getSnapshot();
    commit('delete', {
      ...current,
      nodes: current.nodes.filter((n) => !nodeSet.has(n.id)),
      edges: current.edges.filter(
        (e) => !edgeSet.has(e.id) && !nodeSet.has(e.source) && !nodeSet.has(e.target)
      ),
      configs: Object.fromEntries(Object.entries(current.configs).filter(([key]) => !nodeSet.has(key))),
    });
    setPendingDelete(null);
    message.success(t('workflow.canvas.deleted'));
  }, [pendingDelete, definition.nodes, commit, getSnapshot, t]);

  const handleCopyNodes = useCallback(
    (nodeKeys: string[]) => {
      const fixedKeys = new Set(definition.nodes.filter(isFixedNode).map((node) => node.key));
      const copyable = nodeKeys.filter((key) => !fixedKeys.has(key));
      if (copyable.length === 0) {
        message.info(t('workflow.inspector.deleteDisabled'));
        return;
      }
      const nodes = definition.nodes.filter((n) => copyable.includes(n.key));
      void writeClipboard(buildClipboardPayload(nodes, definition.edges));
      message.success(t('workflow.canvas.copied', { count: nodes.length }));
    },
    [definition, t]
  );

  /** 粘贴 / 复制：key 全部重生成（n1/n2……跳过既有），位置整体偏移；start/end 不参与 */
  const pasteNodes = useCallback(
    (payload: { nodes: WorkflowNode[]; edges: WorkflowDefinition['edges'] }) => {
      const used = new Set(definition.nodes.map((n) => n.key));
      const keyMap: Record<string, string> = {};
      const nodes = payload.nodes
        .filter((node) => !isFixedNode(node))
        .map((node) => {
          const key = nextNodeKey(used);
          used.add(key);
          keyMap[node.key] = key;
          return { ...node, key, position: { x: node.position.x + 40, y: node.position.y + 40 } };
        });
      if (nodes.length === 0) {
        message.info(t('workflow.inspector.deleteDisabled'));
        return;
      }

      // 选中态切到粘贴出来的节点：清掉旧选中，新增节点 selected: true
      const pastedNodes: WorkflowFlowNode[] = nodes.map((node) => ({
        ...flowNodeOf(node, definition.variables),
        selected: true,
      }));
      const pastedEdges: WorkflowFlowEdge[] = payload.edges
        .filter((edge) => keyMap[edge.from] && keyMap[edge.to])
        .map((edge) => {
          const from = keyMap[edge.from];
          const to = keyMap[edge.to];
          const sourceType = nodes.find((node) => node.key === from)?.type;
          const handle = edge.sourceHandle || (sourceType === 'condition' ? 'true' : 'out');
          return {
            id: edgeIdFor(from, handle, to),
            source: from,
            target: to,
            sourceHandle: handle,
            type: 'workflowEdge',
            data: {
              condition: edge.condition,
              onUpstreamFailure: edge.onUpstreamFailure,
              sourceType,
            },
          };
        });

      const current = getSnapshot();
      commit('paste', {
        ...current,
        nodes: [
          ...current.nodes.map((node) => (node.selected ? { ...node, selected: false } : node)),
          ...pastedNodes,
        ],
        edges: [
          ...current.edges.map((edge) => (edge.selected ? { ...edge, selected: false } : edge)),
          ...pastedEdges,
        ],
        configs: {
          ...current.configs,
          ...Object.fromEntries(nodes.map((node) => [node.key, node])),
        },
      });
    },
    [commit, definition.nodes, definition.variables, getSnapshot, t]
  );

  const handleDuplicateNodes = useCallback(
    (nodeKeys: string[]) => {
      if (nodeKeys.length === 0) return;
      const fixedKeys = new Set(definition.nodes.filter(isFixedNode).map((node) => node.key));
      const copyable = nodeKeys.filter((key) => !fixedKeys.has(key));
      if (copyable.length === 0) {
        message.info(t('workflow.inspector.deleteDisabled'));
        return;
      }
      pasteNodes({
        nodes: definition.nodes.filter((n) => copyable.includes(n.key)),
        edges: definition.edges,
      });
    },
    [definition, pasteNodes, t]
  );

  const handleAutoLayout = useCallback(() => {
    const positions = autoLayoutPositions(definition.nodes, definition.edges);
    const current = getSnapshot();
    commit('auto-layout', {
      ...current,
      nodes: current.nodes.map((node) => ({
        ...node,
        position: positions[node.id] || node.position,
      })),
      configs: Object.fromEntries(
        Object.entries(current.configs).map(([key, node]) => [
          key,
          positions[key] ? { ...node, position: positions[key] } : node,
        ])
      ),
    });
    message.success(t('workflow.canvas.autoLayoutDone'));
  }, [commit, definition, getSnapshot, t]);

  /* ── 旧工作流补全入口/出口 ─────────────────────────────────────── */

  const missingStart = !definition.nodes.some((node) => node.type === 'start');
  const missingEnd = !definition.nodes.some((node) => node.type === 'end');

  const completeEntryExit = useCallback(() => {
    const current = exportDefinition();
    const { definition: ensured, addedKeys, changed } = ensureEntryExit(current);
    if (!changed) return;
    const nodes = ensured.nodes.map((node) =>
      addedKeys.includes(node.key)
        ? {
            ...node,
            name:
              node.type === 'start'
                ? t('workflow.node.type.start')
                : node.type === 'end'
                  ? t('workflow.node.type.end')
                  : node.name,
          }
        : node
    );
    const startNode = nodes.find((node) => node.type === 'start');
    const endNode = nodes.find((node) => node.type === 'end');
    const contentNodes = nodes.filter((node) => node.type !== 'start' && node.type !== 'end');
    const incoming = new Set(ensured.edges.map((edge) => edge.to));
    const outgoing = new Set(ensured.edges.map((edge) => edge.from));
    const entries = contentNodes.filter((node) => !incoming.has(node.key));
    const sinks = contentNodes.filter((node) => !outgoing.has(node.key));
    const edges = [...ensured.edges];
    const hasEdge = (from: string, to: string) =>
      edges.some((edge) => edge.from === from && edge.to === to);

    if (startNode && !edges.some((edge) => edge.from === startNode.key)) {
      // start 是普通单出节点（DagValidator：非 condition 只能 1 条出边），
      // 多个入口时只接第一个，其余入口保持独立由用户/校验提示处理。
      for (const entry of entries.slice(0, 1)) {
        if (entry.key === startNode.key || entry.key === endNode?.key || hasEdge(startNode.key, entry.key)) continue;
        edges.push({ id: edgeIdFor(startNode.key, null, entry.key), from: startNode.key, to: entry.key });
      }
    }
    if (endNode && !edges.some((edge) => edge.to === endNode.key)) {
      for (const sink of sinks) {
        if (sink.key === endNode.key || sink.key === startNode?.key || hasEdge(sink.key, endNode.key)) continue;
        edges.push({ id: edgeIdFor(sink.key, null, endNode.key), from: sink.key, to: endNode.key });
      }
    }

    commit('complete-entry-exit', snapshotOf({ ...ensured, nodes, edges }));
    message.success(t('workflow.entryExit.done'));
  }, [commit, exportDefinition, t]);

  /* ── 发布 / 触发 / 校验 ───────────────────────────────────────── */

  const openPublish = useCallback(async () => {
    setPublishLoading(true);
    setPublishOpen(true);
    try {
      await saveNow();
      const res = await validateWorkflow(workflowId, exportDefinition());
      setPublishIssues(res.code === 200 ? issuesOf(res.data) : []);
    } finally {
      setPublishLoading(false);
    }
  }, [workflowId, saveNow, exportDefinition]);

  const confirmPublish = useCallback(async () => {
    setPublishLoading(true);
    try {
      await saveNow();
      const res = await publishWorkflow(workflowId, {});
      if (res.code === 200) {
        message.success(t('workflow.publish.success'));
        setPublishOpen(false);
        await loadDetail();
      } else if (res.code === 422) {
        setPublishIssues(issuesOf(res.data));
      } else if (res.code === 449) {
        message.warning(t('workflow.publish.riskBlocked'));
      } else {
        message.error(res.message || t('workflow.publish.failed'));
      }
    } finally {
      setPublishLoading(false);
    }
  }, [workflowId, loadDetail, saveNow, t]);

  const handleTrigger = useCallback(
    async (inputs: Record<string, unknown>, idempotencyKey: string) => {
      setTriggerLoading(true);
      try {
        const res = await triggerWorkflowRun(workflowId, { input: inputs, idempotencyKey });
        if (res.code === 200 && res.data) {
          message.success(t('workflow.trigger.success'));
          setTriggerOpen(false);
          navigate(`/workflow/run/${res.data.id}`);
        } else if (res.code === 409) {
          message.warning(t('workflow.trigger.notPublished'));
        } else if (res.code === 429) {
          message.warning(t('workflow.trigger.concurrency'));
        } else {
          message.error(res.message || t('workflow.trigger.failed'));
        }
      } finally {
        setTriggerLoading(false);
      }
    },
    [workflowId, navigate, t]
  );

  const openTrigger = useCallback(async () => {
    await saveNow();
    setTriggerOpen(true);
  }, [saveNow]);

  const runValidate = useCallback(async () => {
    setValidateOpen(true);
    setValidateLoading(true);
    try {
      const res = await validateWorkflow(workflowId, exportDefinition());
      setValidateIssues(res.code === 200 ? issuesOf(res.data) : []);
    } finally {
      setValidateLoading(false);
    }
  }, [workflowId, exportDefinition]);

  /* ── 模板 ──────────────────────────────────────────────────────── */

  const applyTemplate = useCallback(
    (next: WorkflowDefinition, templateId: WorkflowTemplateId) => {
      commit('template', snapshotOf(next));
      setTemplatesOpen(false);
      message.success(t('workflow.template.applied', { name: t(`workflow.template.${templateId}.name`) }));
    },
    [commit, t]
  );

  /* ── 409 三选一 ────────────────────────────────────────────────── */

  const handleOverwrite = useCallback(async () => {
    setConflictBusy(true);
    try {
      await overwriteDraft();
      setConflictOpen(false);
      message.success(t('workflow.conflict.overwriteDone'));
    } finally {
      setConflictBusy(false);
    }
  }, [overwriteDraft, t]);

  const handleSaveCopy = useCallback(async () => {
    setConflictBusy(true);
    try {
      // 另存副本 = 服务端新建一条草稿行（onConflict=copy），不是新建一个工作流
      await saveDraftAsCopy();
      setConflictOpen(false);
      message.success(t('workflow.conflict.copyCreated'));
      await loadDetail();
    } finally {
      setConflictBusy(false);
    }
  }, [saveDraftAsCopy, loadDetail, t]);

  const handleDiscard = useCallback(async () => {
    setConflictBusy(true);
    try {
      const res = await getWorkflowDetail(workflowId);
      if (res.code === 200 && res.data?.draft) {
        // 重载同时把乐观锁令牌换回服务端当前值，否则下一次保存还会撞 409
        reloadFromServer(res.data.draft);
        setDetail(res.data);
        setConflictOpen(false);
        message.info(t('workflow.conflict.discarded'));
      }
    } finally {
      setConflictBusy(false);
    }
  }, [workflowId, reloadFromServer, t]);

  /* ── 渲染 ──────────────────────────────────────────────────────── */

  const editingEdge = useMemo(() => {
    if (!editingEdgeId) return null;
    const edge = editor.edges.find((e) => e.id === editingEdgeId);
    if (!edge) return null;
    return {
      id: edge.id,
      from: edge.source,
      to: edge.target,
      sourceHandle: edge.sourceHandle,
      condition: edge.data?.condition,
      onUpstreamFailure: edge.data?.onUpstreamFailure as 'abort' | 'skip' | 'partial' | undefined,
    };
  }, [editingEdgeId, editor.edges]);

  const deleteImpact = useMemo(
    () => (pendingDelete ? computeDeleteImpact(pendingDelete.nodeKeys, definition.edges) : null),
    [pendingDelete, definition.edges]
  );

  const handleOpenNodeConfig = useCallback(
    (key: string) => {
      selectNode(key);
    },
    [selectNode]
  );

  const handleSaveDraft = useCallback(() => {
    void saveNow();
  }, [saveNow]);

  const handleSaveStartVariables = useCallback(
    (variables: Record<string, VariableSpec>, label = 'start-variables') => {
      updateMeta({ variables }, label);
    },
    [updateMeta]
  );

  const canvasEmpty = definition.nodes.filter((node) => node.type !== 'start' && node.type !== 'end').length === 0;

  const inspector = activeNode ? (
    <NodeInspector
      node={activeNode}
      definition={definition}
      onSave={updateNode}
      onSaveMeta={handleSaveStartVariables}
      onRequestDelete={(key) => handleRequestDelete([key], [])}
      remoteOptions
    />
  ) : null;

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 160 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (loadError) {
    return (
      <div style={{ padding: 16 }}>
        {loadError === '403' ? (
          <Alert type="error" showIcon message={t('workflow.error.403')} description={t('workflow.error.403Desc')} />
        ) : loadError === '404' ? (
          <Alert type="error" showIcon message={t('workflow.error.404')} description={t('workflow.error.404Desc')} />
        ) : (
          <Alert type="error" showIcon message={t('workflow.error.generic')} description={loadError} />
        )}
        <Button style={{ marginTop: 16 }} onClick={() => navigate('/workflow')}>
          {t('workflow.error.backToList')}
        </Button>
      </div>
    );
  }

  return (
    <div className="wf-editor-page" style={{ padding: 16 }}>
      <Card size="small" className="wf-editor-toolbar" styles={{ body: { padding: '10px 16px' } }}>
        <Row gutter={[12, 12]} align="middle">
          <Col flex="auto">
            <Space size={10} wrap>
              <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/workflow')} aria-label={t('workflow.error.backToList')} />
              <Title level={5} style={{ margin: 0, color: token.colorText }}>
                {detail?.workflow.name || `#${workflowId}`}
              </Title>
              <Tag color={detail?.workflow.status === 'published' ? 'green' : 'default'}>
                {t(`workflow.status.${detail?.workflow.status || 'draft'}`)}
              </Tag>
              {editor.dirty ? <Tag color="orange">{t('workflow.editor.dirty')}</Tag> : <Tag>{t('workflow.editor.saved')}</Tag>}
              {editor.lastSavedAt && (
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {t('workflow.editor.lastSaved', { time: new Date(editor.lastSavedAt).toLocaleTimeString() })}
                </Text>
              )}
              {selectedNodeKeys.length > 1 && (
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {t('workflow.editor.selectedCount', { count: selectedNodeKeys.length })}
                </Text>
              )}
              {selectedEdgeCount > 0 && (
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {t('workflow.editor.selectedEdges', { count: selectedEdgeCount })}
                </Text>
              )}
            </Space>
          </Col>
          <Col>
            <Space size={8} wrap>
              <Tooltip title={t('workflow.editor.undo')}>
                <Button icon={<UndoOutlined />} disabled={!editor.canUndo} onClick={undo} data-testid="undo" />
              </Tooltip>
              <Tooltip title={t('workflow.editor.redo')}>
                <Button icon={<RedoOutlined />} disabled={!editor.canRedo} onClick={redo} data-testid="redo" />
              </Tooltip>
              <Tooltip title={t('workflow.editor.autoLayout')}>
                <Button icon={<DeploymentUnitOutlined />} onClick={handleAutoLayout} data-testid="auto-layout" />
              </Tooltip>
              <Button icon={<CheckCircleOutlined />} onClick={() => void runValidate()} loading={validateLoading} data-testid="validate-workflow">
                {t('workflow.validate.action')}
              </Button>
              <Button icon={<QuestionCircleOutlined />} onClick={() => setHelpOpen(true)} data-testid="workflow-help">
                {t('workflow.help.action')}
              </Button>
              <Button icon={<AppstoreOutlined />} onClick={() => setTemplatesOpen(true)} data-testid="workflow-templates">
                {t('workflow.template.action')}
              </Button>
              <Button icon={<SaveOutlined />} loading={editor.saving} onClick={handleSaveDraft} data-testid="save-draft">
                {t('common.save')}
              </Button>
              <Button type="primary" icon={<CloudUploadOutlined />} onClick={() => void openPublish()} data-testid="open-publish">
                {t('workflow.publish.action')}
              </Button>
              <Button icon={<PlayCircleOutlined />} onClick={() => void openTrigger()} data-testid="open-trigger">
                {t('workflow.trigger.action')}
              </Button>
              <Button icon={<FileTextOutlined />} onClick={() => navigate(`/workflow/run?workflowId=${workflowId}`)}>
                {t('workflow.run.title')}
              </Button>
            </Space>
          </Col>
        </Row>
        <DefinitionJsonView definition={definition} />
      </Card>

      {(missingStart || missingEnd) && (
        <Alert
          className="wf-entry-exit-banner"
          type="warning"
          showIcon
          message={t('workflow.entryExit.bannerTitle')}
          description={t('workflow.entryExit.bannerDesc')}
          action={
            <Button size="small" type="primary" onClick={completeEntryExit} data-testid="complete-entry-exit">
              {t('workflow.entryExit.action')}
            </Button>
          }
        />
      )}

      <div className="wf-editor-main">
        <div className="wf-editor-palette">
          <NodePalette />
        </div>
        <div className="wf-editor-canvas-shell">
          <WorkflowCanvas
            nodes={editor.nodes}
            edges={editor.edges}
            onNodesChange={editor.onNodesChange}
            onEdgesChange={editor.onEdgesChange}
            onConnect={handleConnect}
            connectionContext={connectionContext}
            onDropNode={handleDropNode}
            onMoveNodes={handleMoveNodes}
            onRequestDelete={handleRequestDelete}
            onCopyNodes={handleCopyNodes}
            onPasteNodes={pasteNodes}
            onDuplicateNodes={handleDuplicateNodes}
            onOpenNodeConfig={handleOpenNodeConfig}
            onEditEdge={setEditingEdgeId}
            onUndo={undo}
            onRedo={redo}
            onSave={handleSaveDraft}
          />
          {canvasEmpty && (
            <div className="wf-canvas-empty" data-testid="canvas-empty-state">
              <div className="wf-canvas-empty-card">
                <Title level={5} style={{ marginTop: 0 }}>
                  {t('workflow.canvas.empty.title')}
                </Title>
                <ol className="wf-canvas-empty-steps">
                  <li>{t('workflow.canvas.empty.step1')}</li>
                  <li>{t('workflow.canvas.empty.step2')}</li>
                  <li>{t('workflow.canvas.empty.step3')}</li>
                  <li>{t('workflow.canvas.empty.step4')}</li>
                </ol>
                <Space>
                  <Button type="primary" size="small" onClick={() => setTemplatesOpen(true)}>
                    {t('workflow.canvas.empty.useTemplate')}
                  </Button>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {t('workflow.canvas.empty.dragHint')}
                  </Text>
                </Space>
              </div>
            </div>
          )}
        </div>
        {!narrow && activeNode && <aside className="wf-editor-inspector-shell">{inspector}</aside>}
      </div>

      {narrow && (
        <Drawer
          open={!!activeNode}
          onClose={() => editor.onNodesChange(editor.nodes.map((node) => ({ id: node.id, type: 'select' as const, selected: false })))}
          placement="right"
          width={480}
          title={activeNode?.name}
          styles={{ body: { padding: 0 } }}
        >
          {inspector}
        </Drawer>
      )}

      <HelpDrawer open={helpOpen} onClose={() => setHelpOpen(false)} />
      <TemplateGallery
        open={templatesOpen}
        definition={definition}
        onCancel={() => setTemplatesOpen(false)}
        onApply={applyTemplate}
      />

      <PublishModal
        open={publishOpen}
        issues={publishIssues}
        loading={publishLoading}
        onCancel={() => setPublishOpen(false)}
        onConfirm={() => void confirmPublish()}
        onRevalidate={() => void openPublish()}
      />

      <TriggerModal
        open={triggerOpen}
        definition={definition}
        workflowName={detail?.workflow.name || ''}
        loading={triggerLoading}
        onCancel={() => setTriggerOpen(false)}
        onSubmit={(inputs, key) => void handleTrigger(inputs, key)}
      />

      <VersionConflictModal
        open={conflictOpen}
        busy={conflictBusy}
        onCancel={() => setConflictOpen(false)}
        onOverwrite={() => void handleOverwrite()}
        onSaveCopy={() => void handleSaveCopy()}
        onDiscard={() => void handleDiscard()}
      />

      <EdgeEditModal
        open={!!editingEdge}
        edge={editingEdge}
        nodes={definition.nodes}
        onCancel={() => setEditingEdgeId(null)}
        onSave={(edgeId, patch) => {
          const current = getSnapshot();
          commit(`edge:${edgeId}`, {
            ...current,
            edges: current.edges.map((edge) =>
              edge.id === edgeId
                ? {
                    ...edge,
                    data: { ...edge.data, condition: patch.condition, onUpstreamFailure: patch.onUpstreamFailure },
                  }
                : edge
            ),
          });
          setEditingEdgeId(null);
        }}
      />

      <Modal
        title={t('workflow.validate.title')}
        open={validateOpen}
        onCancel={() => setValidateOpen(false)}
        footer={<Button onClick={() => setValidateOpen(false)}>{t('common.close')}</Button>}
        width={640}
      >
        {validateLoading ? (
          <Spin />
        ) : validateIssues.length === 0 ? (
          <Alert type="success" showIcon message={t('workflow.validate.ok')} />
        ) : (
          <div className="wf-issue-list" data-testid="validate-issues">
            {validateIssues.map((issue, index) => (
              <div
                key={`${issue.code}-${issue.nodeKey || 'definition'}-${index}`}
                className={`wf-issue wf-issue-${issue.severity.toLowerCase()}`}
                role="button"
                tabIndex={0}
                onClick={() => {
                  if (!issue.nodeKey) return;
                  handleOpenNodeConfig(issue.nodeKey);
                  setValidateOpen(false);
                }}
                onKeyDown={(event) => {
                  if (event.key !== 'Enter' || !issue.nodeKey) return;
                  handleOpenNodeConfig(issue.nodeKey);
                  setValidateOpen(false);
                }}
              >
                <Space size={8} wrap>
                  <Tag color={issue.severity === 'ERROR' ? 'red' : 'orange'}>{issue.severity}</Tag>
                  <Text code>{issue.code}</Text>
                  {issue.nodeKey && <Tag>{issue.nodeKey}</Tag>}
                </Space>
                <div>{issue.message}</div>
              </div>
            ))}
          </div>
        )}
      </Modal>

      <Modal
        title={t('workflow.canvas.deleteTitle')}
        open={!!pendingDelete}
        onCancel={() => setPendingDelete(null)}
        onOk={confirmDelete}
        okText={t('common.delete')}
        okButtonProps={{ danger: true }}
        cancelText={t('common.cancel')}
      >
        {deleteImpact && (
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <Text>
              {t('workflow.canvas.deleteConfirm', { nodes: deleteImpact.nodes.length, edges: deleteImpact.edges.length })}
            </Text>
            {deleteImpact.nodes.length > 0 && (
              <div>
                <Text type="secondary">{t('workflow.canvas.deleteNodes')}: </Text>
                {deleteImpact.nodes.map((key) => (
                  <Tag key={key} style={{ marginBottom: 4 }}>
                    {key}
                  </Tag>
                ))}
              </div>
            )}
          </Space>
        )}
      </Modal>

      {editor.localDraft && (
        <Alert
          style={{ marginTop: 12 }}
          type="info"
          showIcon
          message={t('workflow.editor.localDraftFound')}
          description={
            <Space>
              <Button size="small" type="primary" onClick={editor.restoreLocalDraft}>
                {t('workflow.editor.localDraftRestore')}
              </Button>
              <Button size="small" onClick={editor.discardLocalDraft}>
                {t('workflow.editor.localDraftDiscard')}
              </Button>
            </Space>
          }
        />
      )}
    </div>
  );
};

export default WorkflowEditorPage;
