import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  Controls,
  MiniMap,
  Handle,
  Panel,
  Position,
  ViewportPortal,
  useReactFlow,
  SelectionMode,
  type Connection,
  type EdgeMouseHandler,
  type IsValidConnection,
  type NodeMouseHandler,
  type OnNodesChange,
  type OnEdgesChange,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import '../../../../theme/reactflow.css';
import { Badge, message } from 'antd';
import {
  AuditOutlined,
  BranchesOutlined,
  ClockCircleOutlined,
  CodeOutlined,
  MessageOutlined,
  NotificationOutlined,
  PlayCircleOutlined,
  RobotOutlined,
  StopOutlined,
  SwapOutlined,
  TagsOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { semanticVar } from '../../../../theme/semantics';
import { useTheme } from '../../../../contexts/themeContext';
import {
  HANDLE_IN,
  HANDLE_OUT,
  NODE_WIDTH,
  applyNodeStatuses,
  checkConnection,
  nodeHasInput,
  nodeHeight,
  nodeSummaryTextOf,
  sourceHandlesForNode,
  type ConnectionContext,
  type WorkflowFlowEdge,
  type WorkflowFlowNode,
  type WorkflowNodeData,
} from '../../graph';
import { CLIPBOARD_MIME, readClipboard, type ClipboardPayload } from './clipboard';
import { computeAlignment, type AlignmentGuide, type Box } from './alignment';
import WorkflowEdge from './WorkflowEdge';
import type { WorkflowNodeType } from '../../types';

/* ── 节点视觉（token-only，禁硬编码 hex） ─────────────────────────── */

const NODE_TYPE_ICON: Record<WorkflowNodeType, React.ReactNode> = {
  start: <PlayCircleOutlined />,
  'script-exec': <CodeOutlined />,
  transform: <SwapOutlined />,
  condition: <BranchesOutlined />,
  'ai-analyze': <RobotOutlined />,
  llm: <MessageOutlined />,
  classifier: <TagsOutlined />,
  code: <CodeOutlined />,
  approval: <AuditOutlined />,
  notify: <NotificationOutlined />,
  wait: <ClockCircleOutlined />,
  end: <StopOutlined />,
};

/** 运行态状态色：全部来自 src/theme 语义变量（深/浅色自动切换） */
const STATUS_VAR: Record<string, string> = {
  pending: semanticVar('pending'),
  running: semanticVar('running'),
  succeeded: semanticVar('succeeded'),
  failed: semanticVar('failed'),
  skipped: semanticVar('skipped'),
  waiting: semanticVar('approvalWaiting'),
  suspended: semanticVar('approvalWaiting'),
  cancelled: semanticVar('cancelled'),
  timeout: semanticVar('timeout'),
};

/** condition 双出把手的纵向位置（右上 true / 右下 false）；classifier 按 handle 数均分 */
const HANDLE_TOP_RATIO: Record<string, number> = {
  [HANDLE_IN]: 0.5,
  [HANDLE_OUT]: 0.5,
  true: 0.32,
  false: 0.68,
};

function handleTopRatio(
  nodeType: WorkflowNodeType,
  handleId: string,
  index: number,
  count: number
): number {
  if (nodeType === 'condition' && (handleId === 'true' || handleId === 'false')) {
    return HANDLE_TOP_RATIO[handleId];
  }
  if (nodeType === 'classifier') return count <= 1 ? 0.5 : (index + 1) / (count + 1);
  return 0.5;
}

function handleLabelOf(handleId: string): string {
  if (handleId === 'true') return 'IF';
  if (handleId === 'false') return 'ELSE';
  return handleId;
}

interface WorkflowNodeCardProps {
  data: WorkflowNodeData;
  selected: boolean;
}

const WorkflowNodeCard = React.memo(function WorkflowNodeCard({ data, selected }: WorkflowNodeCardProps) {
  const { t } = useTranslation();
  const statusColor = data.status ? STATUS_VAR[data.status] : undefined;
  const handles = sourceHandlesForNode(data);
  const height = nodeHeight(data.nodeType, handles.length);
  const summary = data.summary || nodeSummaryTextOf(data.nodeType, data);

  return (
    <div
      className="wf-node"
      data-status={data.status || 'idle'}
      data-selected={selected ? 'true' : 'false'}
      aria-label={`${data.name} ${t(`workflow.node.type.${data.nodeType}`)}`}
      style={{
        width: NODE_WIDTH,
        height,
        borderColor: selected ? 'var(--es-color-primary)' : statusColor || 'var(--es-color-border)',
      }}
    >
      {nodeHasInput(data.nodeType) && (
        <Handle type="target" position={Position.Left} id={HANDLE_IN} style={{ top: '50%' }} />
      )}
      <div className="wf-node-head">
        <span className="wf-node-icon" style={{ color: statusColor || 'var(--es-color-primary)' }}>
          {NODE_TYPE_ICON[data.nodeType]}
        </span>
        <span className="wf-node-name">{data.name}</span>
        {data.status && (
          <Badge
            status={
              data.status === 'running'
                ? 'processing'
                : data.status === 'succeeded'
                  ? 'success'
                  : data.status === 'failed'
                    ? 'error'
                    : 'default'
            }
          />
        )}
      </div>
      {summary && (
        <div className="wf-node-summary" title={summary}>
          {summary}
        </div>
      )}
      <div className="wf-node-foot">
        <span className="wf-node-key">{data.key}</span>
        <span className="wf-node-type">{t(`workflow.node.type.${data.nodeType}`)}</span>
      </div>
      {handles.map((handleId, index) => (
        <Handle
          key={handleId}
          type="source"
          position={Position.Right}
          id={handleId}
          style={{ top: `${handleTopRatio(data.nodeType, handleId, index, handles.length) * 100}%` }}
        >
          {handleId !== HANDLE_OUT && (
            <span className="wf-handle-label">{handleLabelOf(handleId)}</span>
          )}
        </Handle>
      ))}
    </div>
  );
});

const nodeTypes = { workflowNode: WorkflowNodeCard };
const edgeTypes = { workflowEdge: WorkflowEdge };

/**
 * 提升到模块级的常量：React Flow 的 StoreUpdater 按 props 身份同步这些字段，
 * 每次渲染都传新对象会让 store 反复 setState → 节点重新测量 → onNodesChange →
 * 再渲染，形成 "Maximum update depth exceeded" 自环。
 */
const FIT_VIEW_OPTIONS = { padding: 0.2, maxZoom: 1 };
const TRANSLATE_EXTENT: [[number, number], [number, number]] = [
  [-4000, -4000],
  [8000, 8000],
];
const SNAP_GRID: [number, number] = [20, 20];
const ARIA_LABEL_CONFIG = {
  'node.a11yDescription.default':
    '工作流节点：Enter 打开配置，方向键移动 20px（Shift 4px），Delete 删除，Escape 取消选择',
  'edge.a11yDescription.default': '工作流连线：双击编辑条件',
};

/* ── 画布 ─────────────────────────────────────────────────────────── */

export interface WorkflowCanvasProps {
  nodes: WorkflowFlowNode[];
  edges: WorkflowFlowEdge[];
  onNodesChange: OnNodesChange<WorkflowFlowNode>;
  onEdgesChange: OnEdgesChange<WorkflowFlowEdge>;
  /** 连线请求（已过 checkConnection，父组件负责落库） */
  onConnect: (connection: Connection) => void;
  connectionContext: ConnectionContext;
  onDropNode: (type: WorkflowNodeType, position: { x: number; y: number }) => void;
  /** 拖拽/方向键移动结束，父组件只落一次位置（拖拽中不序列化，NF-3） */
  onMoveNodes: (moves: Array<{ id: string; position: { x: number; y: number } }>) => void;
  onRequestDelete: (nodeKeys: string[], edgeIds: string[]) => void;
  onCopyNodes: (nodeKeys: string[]) => void;
  onPasteNodes: (payload: ClipboardPayload) => void;
  onDuplicateNodes: (nodeKeys: string[]) => void;
  /** @deprecated 选中态由 React Flow 的 node.selected 派生；保留仅为运行监控页兼容 */
  onSelectionChange?: (nodeKeys: string[], edgeIds: string[]) => void;
  onOpenNodeConfig: (key: string) => void;
  onEditEdge: (edgeId: string) => void;
  onUndo: () => void;
  onRedo: () => void;
  onSave: () => void;
  readOnly?: boolean;
  /** 只读态（运行监控）下的节点状态着色 */
  statuses?: Record<string, string>;
}

const KEY_MOVE = 20;
const KEY_MOVE_FINE = 4;

function WorkflowCanvasInner(props: WorkflowCanvasProps) {
  const {
    nodes, edges, onNodesChange, onEdgesChange, onConnect, connectionContext,
    onDropNode, onMoveNodes, onRequestDelete, onCopyNodes, onPasteNodes, onDuplicateNodes,
    onOpenNodeConfig, onEditEdge, onUndo, onRedo, onSave,
    readOnly = false, statuses,
  } = props;
  const { t } = useTranslation();
  const { screenToFlowPosition } = useReactFlow();
  const { isDark } = useTheme();
  const [guides, setGuides] = useState<AlignmentGuide[]>([]);
  const wrapperRef = useRef<HTMLDivElement>(null);

  const selectedNodeKeys = useMemo(() => nodes.filter((n) => n.selected).map((n) => n.id), [nodes]);
  const selectedEdgeIds = useMemo(() => edges.filter((e) => e.selected).map((e) => e.id), [edges]);

  // 编辑态 statuses 缺省 → 原样返回 nodes（零克隆）；监控态只克隆状态变化的节点
  const decoratedNodes = useMemo(() => applyNodeStatuses(nodes, statuses), [nodes, statuses]);

  const rejectConnection = useCallback(
    (connection: { source: string; target: string; sourceHandle?: string | null }) => {
      const rejection = checkConnection(connectionContext, connection);
      if (!rejection) return false;
      message.warning(t(`workflow.canvas.reject.${rejection}`));
      return true;
    },
    [connectionContext, t]
  );

  const handleConnect = useCallback(
    (connection: Connection) => {
      if (
        rejectConnection({
          source: connection.source,
          target: connection.target,
          sourceHandle: connection.sourceHandle,
        })
      ) {
        return;
      }
      onConnect(connection);
    },
    [onConnect, rejectConnection]
  );

  /* ── 对齐线与吸附 ─────────────────────────────────────────────── */

  const alignmentInputs = useCallback(
    (node: WorkflowFlowNode, dragged: WorkflowFlowNode[]) => {
      const draggedIds = new Set(dragged.map((n) => n.id));
      const movingBox: Box = {
        x: node.position.x,
        y: node.position.y,
        width: NODE_WIDTH,
        height: nodeHeight(node.data.nodeType, sourceHandlesForNode(node.data).length),
      };
      const others = nodes
        .filter((n) => !draggedIds.has(n.id))
        .map<Box>((n) => ({
          x: n.position.x,
          y: n.position.y,
          width: NODE_WIDTH,
          height: nodeHeight(n.data.nodeType, sourceHandlesForNode(n.data).length),
        }));
      return { movingBox, others };
    },
    [nodes]
  );

  const handleNodeDrag = useCallback(
    (_event: unknown, node: WorkflowFlowNode, dragged: WorkflowFlowNode[]) => {
      const { movingBox, others } = alignmentInputs(node, dragged);
      setGuides(computeAlignment(movingBox, others).guides);
    },
    [alignmentInputs]
  );

  const handleNodeDragStop = useCallback(
    (_event: unknown, node: WorkflowFlowNode, dragged: WorkflowFlowNode[]) => {
      setGuides([]);
      const { movingBox, others } = alignmentInputs(node, dragged);
      const { snapped } = computeAlignment(movingBox, others);
      const dx = snapped.x - movingBox.x;
      const dy = snapped.y - movingBox.y;
      // 无论是否吸附都提交一次：commit 对结构相同的文档 no-op，但未吸附的位移必须落库
      onMoveNodes(
        dragged.map((n) => ({
          id: n.id,
          position: { x: Math.round(n.position.x + dx), y: Math.round(n.position.y + dy) },
        }))
      );
    },
    [alignmentInputs, onMoveNodes]
  );

  /* ── 面板拖入 ─────────────────────────────────────────────────── */

  const handleDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
  }, []);

  const handleDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();
      const raw = event.dataTransfer.getData(CLIPBOARD_MIME);
      if (!raw) return;
      let payload: { type?: WorkflowNodeType };
      try {
        payload = JSON.parse(raw);
      } catch {
        return;
      }
      if (!payload?.type) return;
      const position = screenToFlowPosition({ x: event.clientX, y: event.clientY });
      onDropNode(payload.type, { x: Math.round(position.x - NODE_WIDTH / 2), y: Math.round(position.y - 36) });
    },
    [onDropNode, screenToFlowPosition]
  );

  /** 调色板的键盘等价入口（HTML5 拖拽对键盘不可达）：自定义事件带屏幕坐标，走同一条落点计算 */
  const handlePaletteDropEvent = useCallback(
    (event: Event) => {
      const detail = (event as CustomEvent<{ type?: WorkflowNodeType; clientX: number; clientY: number }>).detail;
      if (!detail?.type) return;
      const position = screenToFlowPosition({ x: detail.clientX, y: detail.clientY });
      onDropNode(detail.type, { x: Math.round(position.x - NODE_WIDTH / 2), y: Math.round(position.y - 36) });
    },
    [onDropNode, screenToFlowPosition]
  );

  useEffect(() => {
    const element = wrapperRef.current;
    if (!element || readOnly) return;
    element.addEventListener('wf:palette-drop', handlePaletteDropEvent);
    return () => element.removeEventListener('wf:palette-drop', handlePaletteDropEvent);
  }, [handlePaletteDropEvent, readOnly]);

  /* ── 键盘快捷键（选项 3 全集） ────────────────────────────────── */

  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return;
      const mod = event.ctrlKey || event.metaKey;
      const key = event.key.toLowerCase();

      if (mod && key === 'z') {
        event.preventDefault();
        if (event.shiftKey) onRedo();
        else onUndo();
        return;
      }
      if (mod && key === 'y') {
        event.preventDefault();
        onRedo();
        return;
      }
      if (mod && key === 's') {
        event.preventDefault();
        onSave();
        return;
      }
      if (mod && key === 'a') {
        event.preventDefault();
        onNodesChange(nodes.map((n) => ({ id: n.id, type: 'select', selected: true })));
        onEdgesChange(edges.map((e) => ({ id: e.id, type: 'select', selected: true })));
        return;
      }
      if (mod && key === 'c') {
        event.preventDefault();
        onCopyNodes(selectedNodeKeys);
        return;
      }
      if (mod && key === 'v') {
        event.preventDefault();
        void readClipboard().then((payload) => {
          if (payload) onPasteNodes(payload);
        });
        return;
      }
      if (mod && key === 'd') {
        event.preventDefault();
        onDuplicateNodes(selectedNodeKeys);
        return;
      }
      if (event.key === 'Delete' || event.key === 'Backspace') {
        if (selectedNodeKeys.length === 0 && selectedEdgeIds.length === 0) return;
        event.preventDefault();
        onRequestDelete(selectedNodeKeys, selectedEdgeIds);
        return;
      }
      if (event.key === 'Enter' && selectedNodeKeys.length === 1) {
        event.preventDefault();
        onOpenNodeConfig(selectedNodeKeys[0]);
        return;
      }
      if (event.key === 'Escape') {
        onNodesChange(nodes.map((n) => ({ id: n.id, type: 'select', selected: false })));
        onEdgesChange(edges.map((e) => ({ id: e.id, type: 'select', selected: false })));
        return;
      }
      if (event.key.startsWith('Arrow') && selectedNodeKeys.length > 0) {
        // 自研 20px / Shift 4px（React Flow 内置是 5px 且不进撤销栈）
        event.preventDefault();
        event.stopPropagation();
        const step = event.shiftKey ? KEY_MOVE_FINE : KEY_MOVE;
        const dx = event.key === 'ArrowLeft' ? -step : event.key === 'ArrowRight' ? step : 0;
        const dy = event.key === 'ArrowUp' ? -step : event.key === 'ArrowDown' ? step : 0;
        onMoveNodes(
          nodes
            .filter((n) => n.selected)
            .map((n) => ({ id: n.id, position: { x: n.position.x + dx, y: n.position.y + dy } }))
        );
      }
    },
    [
      nodes, edges, onNodesChange, onEdgesChange, selectedNodeKeys, selectedEdgeIds,
      onUndo, onRedo, onSave, onCopyNodes, onPasteNodes, onDuplicateNodes,
      onRequestDelete, onOpenNodeConfig, onMoveNodes,
    ]
  );

  const isValidConnection = useCallback<IsValidConnection<WorkflowFlowEdge>>(
    (connection) =>
      checkConnection(connectionContext, {
        source: connection.source,
        target: connection.target,
        sourceHandle: connection.sourceHandle,
      }) === null,
    [connectionContext]
  );

  const handleNodeDoubleClick = useCallback<NodeMouseHandler<WorkflowFlowNode>>(
    (_event, node) => onOpenNodeConfig(node.id),
    [onOpenNodeConfig]
  );

  const handleEdgeDoubleClick = useCallback<EdgeMouseHandler<WorkflowFlowEdge>>(
    (_event, edge) => onEditEdge(edge.id),
    [onEditEdge]
  );

  const handlePaneClick = useCallback(() => {
    setGuides([]);
  }, []);

  return (
    <div
      className="wf-canvas"
      ref={wrapperRef}
      role="application"
      aria-label={t('workflow.canvas.ariaLabel')}
      onKeyDown={readOnly ? undefined : handleKeyDown}
      onDragOver={readOnly ? undefined : handleDragOver}
      onDrop={readOnly ? undefined : handleDrop}
      data-testid="workflow-canvas"
    >
      <ReactFlow<WorkflowFlowNode, WorkflowFlowEdge>
        nodes={decoratedNodes}
        edges={edges}
        onNodesChange={readOnly ? undefined : onNodesChange}
        onEdgesChange={readOnly ? undefined : onEdgesChange}
        onConnect={readOnly ? undefined : handleConnect}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        colorMode={isDark ? 'dark' : 'light'}
        fitView
        fitViewOptions={FIT_VIEW_OPTIONS}
        minZoom={0.2}
        maxZoom={2}
        translateExtent={TRANSLATE_EXTENT}
        snapToGrid
        snapGrid={SNAP_GRID}
        onlyRenderVisibleElements
        nodesDraggable={!readOnly}
        nodesConnectable={!readOnly}
        nodesFocusable
        elementsSelectable
        elevateNodesOnSelect
        selectionOnDrag={!readOnly}
        selectionMode={SelectionMode.Full}
        panOnDrag={false}
        panActivationKeyCode="Space"
        multiSelectionKeyCode="Control"
        // 删除/移动由画布自己的键盘处理器接管（带确认、进撤销栈、可对齐吸附）
        deleteKeyCode={null}
        isValidConnection={readOnly ? undefined : isValidConnection}
        onNodeDrag={readOnly ? undefined : handleNodeDrag}
        onNodeDragStop={readOnly ? undefined : handleNodeDragStop}
        onNodeDoubleClick={readOnly ? undefined : handleNodeDoubleClick}
        onEdgeDoubleClick={readOnly ? undefined : handleEdgeDoubleClick}
        onPaneClick={handlePaneClick}
        ariaLabelConfig={ARIA_LABEL_CONFIG}
      >
        <Background gap={20} size={1} />
        <Controls showInteractive={false} />
        <MiniMap
          pannable
          zoomable
          nodeStrokeWidth={3}
          nodeColor={(node) => {
            const status = statuses?.[node.id];
            return status ? STATUS_VAR[status] || semanticVar('pending') : 'var(--es-color-border)';
          }}
        />
        <ViewportPortal>
          <svg className="wf-alignment-layer" aria-hidden="true">
            {guides.map((guide, index) => (
              <line
                key={`${guide.orientation}-${guide.position}-${index}`}
                x1={guide.orientation === 'vertical' ? guide.position : guide.start}
                y1={guide.orientation === 'vertical' ? guide.start : guide.position}
                x2={guide.orientation === 'vertical' ? guide.position : guide.end}
                y2={guide.orientation === 'vertical' ? guide.end : guide.position}
                stroke="var(--es-color-primary)"
                strokeWidth={1}
                strokeDasharray="4 3"
              />
            ))}
          </svg>
        </ViewportPortal>
        {!readOnly && (
          <Panel position="top-right">
            <span className="wf-canvas-hint">{t('workflow.canvas.hint')}</span>
          </Panel>
        )}
      </ReactFlow>
    </div>
  );
}

export default function WorkflowCanvas(props: WorkflowCanvasProps) {
  return (
    <ReactFlowProvider>
      <WorkflowCanvasInner {...props} />
    </ReactFlowProvider>
  );
}
