import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Col,
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
  ArrowLeftOutlined,
  CloudUploadOutlined,
  DeploymentUnitOutlined,
  PlayCircleOutlined,
  RedoOutlined,
  SaveOutlined,
  UndoOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { theme } from 'antd';
import {
  getWorkflowDetail,
  issuesOf,
  publishWorkflow,
  triggerWorkflowRun,
  validateWorkflow,
} from '../api';
import type {
  WorkflowDefinition,
  WorkflowDetail,
  WorkflowNode,
  WorkflowNodeType,
  WorkflowVersionDetail,
  ValidationIssue,
} from '../types';
import {
  autoLayoutPositions,
  checkConnection,
  computeDeleteImpact,
  createWorkflowNode,
  edgeHandleOf,
  edgeIdFor,
  flowNodeOf,
  nextNodeKey,
  type WorkflowFlowEdge,
  type WorkflowFlowNode,
} from '../graph';
import WorkflowCanvas from './canvas/WorkflowCanvas';
import NodePalette from './NodePalette';
import NodeFormPanel from './NodeFormPanel';
import DefinitionJsonView from './DefinitionJsonView';
import { EdgeEditModal, PublishModal, TriggerModal, VersionConflictModal } from './modals';
import { useWorkflowEditor } from './useWorkflowEditor';
import { buildClipboardPayload, writeClipboard } from './canvas/clipboard';
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

const WorkflowEditorPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const workflowId = Number(id);
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { token } = theme.useToken();

  const [detail, setDetail] = useState<WorkflowDetail | null>(null);
  const [draft, setDraft] = useState<WorkflowVersionDetail>(EMPTY_DRAFT);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [formKey, setFormKey] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<{ nodeKeys: string[]; edgeIds: string[] } | null>(null);
  const [editingEdgeId, setEditingEdgeId] = useState<string | null>(null);
  const [publishOpen, setPublishOpen] = useState(false);
  const [publishIssues, setPublishIssues] = useState<ValidationIssue[]>([]);
  const [publishLoading, setPublishLoading] = useState(false);
  const [triggerOpen, setTriggerOpen] = useState(false);
  const [triggerLoading, setTriggerLoading] = useState(false);
  const [conflictOpen, setConflictOpen] = useState(false);
  const [conflictBusy, setConflictBusy] = useState(false);

  const loadDetail = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const res = await getWorkflowDetail(workflowId);
      if (res.code === 200 && res.data) {
        setDetail(res.data);
        setDraft(res.data.draft || EMPTY_DRAFT);
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
    saveNow,
    undo,
    redo,
    overwriteDraft,
    saveDraftAsCopy,
    reloadFromServer,
    getSnapshot,
  } = editor;

  // 连接校验只需要节点类型/边结构；definition 只在结构提交时换身份
  const connectionContext = useMemo(
    () => ({ nodes: definition.nodes, edges: definition.edges }),
    [definition]
  );

  const selectedNodeCount = useMemo(() => editor.nodes.filter((node) => node.selected).length, [editor.nodes]);
  const selectedEdgeCount = useMemo(() => editor.edges.filter((edge) => edge.selected).length, [editor.edges]);

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

  const handleRequestDelete = useCallback((nodeKeys: string[], edgeIds: string[]) => {
    if (nodeKeys.length === 0 && edgeIds.length === 0) return;
    setPendingDelete({ nodeKeys, edgeIds });
  }, []);

  const confirmDelete = useCallback(() => {
    if (!pendingDelete) return;
    const nodeSet = new Set(pendingDelete.nodeKeys);
    const current = getSnapshot();
    commit('delete', {
      ...current,
      nodes: current.nodes.filter((n) => !nodeSet.has(n.id)),
      edges: current.edges.filter((e) => !nodeSet.has(e.source) && !nodeSet.has(e.target)),
      configs: Object.fromEntries(Object.entries(current.configs).filter(([key]) => !nodeSet.has(key))),
    });
    setFormOpen(false);
    setPendingDelete(null);
    message.success(t('workflow.canvas.deleted'));
  }, [pendingDelete, commit, getSnapshot, t]);

  const handleCopyNodes = useCallback(
    (nodeKeys: string[]) => {
      if (nodeKeys.length === 0) return;
      const nodes = definition.nodes.filter((n) => nodeKeys.includes(n.key));
      void writeClipboard(buildClipboardPayload(nodes, definition.edges));
      message.success(t('workflow.canvas.copied', { count: nodes.length }));
    },
    [definition, t]
  );

  /** 粘贴 / 复制：key 全部重生成（n1/n2……跳过既有），位置整体偏移 */
  const pasteNodes = useCallback(
    (payload: { nodes: WorkflowNode[]; edges: WorkflowDefinition['edges'] }) => {
      const used = new Set(definition.nodes.map((n) => n.key));
      const keyMap: Record<string, string> = {};
      const nodes = payload.nodes.map((node) => {
        const key = nextNodeKey(used);
        used.add(key);
        keyMap[node.key] = key;
        return { ...node, key, position: { x: node.position.x + 40, y: node.position.y + 40 } };
      });

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
          const handle = edgeHandleOf(nodes, {
            from,
            sourceHandle: edge.sourceHandle,
            condition: edge.condition,
          });
          const sourceType = nodes.find((node) => node.key === from)?.type;
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
    [commit, definition.nodes, definition.variables, getSnapshot]
  );

  const handleDuplicateNodes = useCallback(
    (nodeKeys: string[]) => {
      if (nodeKeys.length === 0) return;
      pasteNodes({
        nodes: definition.nodes.filter((n) => nodeKeys.includes(n.key)),
        edges: definition.edges,
      });
    },
    [definition, pasteNodes]
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

  /* ── 发布 / 触发 ───────────────────────────────────────────────── */

  const openPublish = useCallback(async () => {
    setPublishLoading(true);
    setPublishOpen(true);
    try {
      // 校验本地草稿（含未保存改动）：预校验要回答的是"用户眼前这份定义能不能发布"。
      // 空 body 会让服务端返回 400，而这里会把非 200 一律当成"无 issues"——
      // 那正是 ERROR 工作流被显示成"校验通过"的原因。
      const res = await validateWorkflow(workflowId, definition);
      setPublishIssues(res.code === 200 ? issuesOf(res.data) : []);
    } finally {
      setPublishLoading(false);
    }
  }, [workflowId, definition]);

  const confirmPublish = useCallback(async () => {
    setPublishLoading(true);
    try {
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
  }, [workflowId, loadDetail, t]);

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

  const selectedNode = useMemo(
    () => definition.nodes.find((n) => n.key === formKey) || null,
    [definition.nodes, formKey]
  );

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

  const handleOpenNodeConfig = useCallback((key: string) => {
    setFormKey(key);
    setFormOpen(true);
  }, []);

  const handleSaveDraft = useCallback(() => {
    void saveNow();
  }, [saveNow]);

  // Escape 关闭配置抽屉（画布内 Escape 由画布自己消费）
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && formOpen) setFormOpen(false);
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [formOpen]);

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
    <div style={{ padding: 16 }}>
      <Card size="small" style={{ marginBottom: 12 }} styles={{ body: { padding: '12px 16px' } }}>
        <Row gutter={[12, 12]} align="middle">
          <Col flex="auto">
            <Space size={12} wrap>
              <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/workflow')} />
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
              {selectedNodeCount > 0 && (
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {t('workflow.editor.selectedCount', { count: selectedNodeCount })}
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
              <Button icon={<SaveOutlined />} loading={editor.saving} onClick={handleSaveDraft} data-testid="save-draft">
                {t('common.save')}
              </Button>
              <Button type="primary" icon={<CloudUploadOutlined />} onClick={() => void openPublish()} data-testid="open-publish">
                {t('workflow.publish.action')}
              </Button>
              <Button icon={<PlayCircleOutlined />} onClick={() => setTriggerOpen(true)} data-testid="open-trigger">
                {t('workflow.trigger.action')}
              </Button>
              <Button onClick={() => navigate(`/workflow/run?workflowId=${workflowId}`)}>
                {t('workflow.run.title')}
              </Button>
            </Space>
          </Col>
        </Row>
      </Card>

      <Row gutter={[12, 12]}>
        <Col flex="220px" style={{ minWidth: 200 }}>
          <NodePalette />
        </Col>
        <Col flex="auto">
          <Card size="small" className="wf-editor-canvas-card">
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
          </Card>
        </Col>
      </Row>

      <div style={{ marginTop: 12 }}>
        <DefinitionJsonView definition={definition} />
      </div>

      <NodeFormPanel
        node={selectedNode}
        open={formOpen && !!selectedNode}
        onClose={() => setFormOpen(false)}
        onSave={updateNode}
        onRequestDelete={(key) => handleRequestDelete([key], [])}
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
