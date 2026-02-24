import React, { useMemo } from 'react';
import { Button, Tag, Space, theme } from 'antd';
import {
  CheckCircleOutlined, CloseCircleOutlined, LoadingOutlined,
  ClockCircleOutlined, PauseCircleOutlined, QuestionCircleOutlined,
} from '@ant-design/icons';
import {
  ReactFlow, Background, Controls,
  type Node, type Edge, Position,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useTranslation } from 'react-i18next';
import type { ExecutionPlan } from '../../../types';

interface DagPlanViewProps {
  plan: ExecutionPlan;
  currentStepIndex: number;
  checkpointSteps: Set<number>;
  onApproveCheckpoint: (stepIndex: number) => void;
  onRejectCheckpoint: (stepIndex: number) => void;
  checkpointLoading?: number | null;
}

const STATUS_CONFIG: Record<string, { color: string; icon: React.ReactNode }> = {
  pending:          { color: '#d9d9d9', icon: <ClockCircleOutlined /> },
  running:          { color: '#1890ff', icon: <LoadingOutlined spin /> },
  completed:        { color: '#52c41a', icon: <CheckCircleOutlined /> },
  failed:           { color: '#ff4d4f', icon: <CloseCircleOutlined /> },
  skipped:          { color: '#faad14', icon: <QuestionCircleOutlined /> },
  waiting_approval: { color: '#722ed1', icon: <PauseCircleOutlined /> },
};

const DagPlanView: React.FC<DagPlanViewProps> = ({
  plan, currentStepIndex, checkpointSteps,
  onApproveCheckpoint, onRejectCheckpoint, checkpointLoading,
}) => {
  const { token } = theme.useToken();
  const { t } = useTranslation();

  const { nodes, edges } = useMemo(() => {
    const steps = plan.steps;
    const nodeList: Node[] = [];
    const edgeList: Edge[] = [];

    // Calculate depth via dependsOn
    const depthMap = new Map<number, number>();
    const calculateDepth = (idx: number): number => {
      if (depthMap.has(idx)) return depthMap.get(idx)!;
      const step = steps.find(s => s.index === idx);
      if (!step?.dependsOn?.length) { depthMap.set(idx, 0); return 0; }
      const maxDep = Math.max(...step.dependsOn.map(d => calculateDepth(d)));
      const depth = maxDep + 1;
      depthMap.set(idx, depth);
      return depth;
    };
    steps.forEach(s => calculateDepth(s.index));

    // Group by depth layer
    const layers = new Map<number, number[]>();
    steps.forEach(s => {
      const depth = depthMap.get(s.index) || 0;
      if (!layers.has(depth)) layers.set(depth, []);
      layers.get(depth)!.push(s.index);
    });

    const X_GAP = 280;
    const Y_GAP = 100;

    steps.forEach(step => {
      const depth = depthMap.get(step.index) || 0;
      const layerNodes = layers.get(depth) || [];
      const posInLayer = layerNodes.indexOf(step.index);

      const status = step.status || (step.index < currentStepIndex ? 'completed'
        : step.index === currentStepIndex ? 'running' : 'pending');
      const statusConf = STATUS_CONFIG[status] || STATUS_CONFIG.pending;
      const isCheckpoint = step.checkpoint && checkpointSteps.has(step.index);

      nodeList.push({
        id: String(step.index),
        position: { x: depth * X_GAP, y: posInLayer * Y_GAP },
        data: {
          label: (
            <div style={{ padding: 8, minWidth: 180 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginBottom: 4 }}>
                {statusConf.icon}
                <strong>#{step.index}</strong>
                {step.agent && <Tag style={{ marginRight: 0 }}>{step.agent}</Tag>}
              </div>
              <div style={{ fontSize: 12, marginBottom: step.checkpoint ? 6 : 0 }}>
                {step.description}
              </div>
              {step.condition && (
                <div style={{ fontSize: 11, color: token.colorTextSecondary }}>
                  {t('dag.step.condition', 'Condition')}: {step.condition}
                </div>
              )}
              {isCheckpoint && (
                <Space style={{ marginTop: 4 }}>
                  <Button
                    size="small" type="primary"
                    loading={checkpointLoading === step.index}
                    onClick={(e) => { e.stopPropagation(); onApproveCheckpoint(step.index); }}
                  >
                    {t('dag.checkpoint.approve', 'Approve')}
                  </Button>
                  <Button
                    size="small" danger
                    loading={checkpointLoading === step.index}
                    onClick={(e) => { e.stopPropagation(); onRejectCheckpoint(step.index); }}
                  >
                    {t('dag.checkpoint.reject', 'Reject')}
                  </Button>
                </Space>
              )}
            </div>
          ),
        },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: {
          border: `2px solid ${statusConf.color}`,
          borderRadius: token.borderRadius,
          background: token.colorBgContainer,
          padding: 0,
        },
      });

      // Edges from dependsOn
      if (step.dependsOn) {
        step.dependsOn.forEach(dep => {
          edgeList.push({
            id: `e${dep}-${step.index}`,
            source: String(dep),
            target: String(step.index),
            animated: status === 'running',
            style: {
              stroke: step.condition ? token.colorWarning : token.colorPrimary,
              strokeDasharray: step.condition ? '5 5' : undefined,
            },
            label: step.condition ? step.condition : undefined,
            labelStyle: { fontSize: 10 },
          });
        });
      }
    });

    return { nodes: nodeList, edges: edgeList };
  }, [plan, currentStepIndex, checkpointSteps, checkpointLoading, token, t, onApproveCheckpoint, onRejectCheckpoint]);

  return (
    <div style={{ width: '100%', height: 400, border: `1px solid ${token.colorBorderSecondary}`, borderRadius: token.borderRadius }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        fitView
        attributionPosition="bottom-left"
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
      >
        <Background />
        <Controls />
      </ReactFlow>
    </div>
  );
};

export default DagPlanView;
