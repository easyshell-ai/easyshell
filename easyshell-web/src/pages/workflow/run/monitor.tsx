import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Popconfirm,
  Row,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import { ArrowLeftOutlined, ReloadOutlined, StopOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { theme } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { cancelWorkflowRun, getWorkflowRun, getWorkflowVersion } from '../api';
import type { WorkflowDefinition, WorkflowNodeRun, WorkflowRunDetail } from '../types';
import { definitionToFlow } from '../graph';
import { nodeRunStatusMap } from '../status';
import { formatDateTime } from '../../../utils/format';
import WorkflowCanvas from '../editor/canvas/WorkflowCanvas';
import RunLogPanel from './RunLogPanel';
import '../workflow.css';

const { Title, Text } = Typography;

const TERMINAL = ['succeeded', 'failed', 'cancelled', 'timeout'];

/**
 * 运行详情（V0 只读画布）：复用 DagPlanView 的只读渲染范式 + 同一套分层 depth 算法，
 * 节点按 nodeRun 状态着色（pending/running/succeeded/failed/skipped/suspended）。
 */
const WorkflowRunMonitorPage: React.FC = () => {
  const { runId } = useParams<{ runId: string }>();
  const { t } = useTranslation();
  const { token } = theme.useToken();

  const [detail, setDetail] = useState<WorkflowRunDetail | null>(null);
  const [definition, setDefinition] = useState<WorkflowDefinition | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState('');

  const load = useCallback(async () => {
    if (!runId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await getWorkflowRun(runId);
      if (res.code === 200 && res.data) {
        setDetail(res.data);
      } else if (res.code === 404) {
        setError('404');
      } else if (res.code === 403) {
        setError('403');
      } else {
        setError(res.message || 'error');
      }
    } catch {
      setError('error');
    } finally {
      setLoading(false);
    }
  }, [runId]);

  useEffect(() => {
    void load();
  }, [load]);

  // 画布要画边，需要不可变版本的 definition：run.workflowId + run.versionId 反查
  useEffect(() => {
    const run = detail?.run;
    if (!run?.workflowId || !run?.versionId) return;
    let cancelled = false;
    getWorkflowVersion(run.workflowId, run.versionId).then((res) => {
      if (!cancelled && res.code === 200 && res.data) setDefinition(res.data.definition);
    });
    return () => {
      cancelled = true;
    };
  }, [detail?.run?.workflowId, detail?.run?.versionId]);

  // 终态后停止轮询
  useEffect(() => {
    const status = detail?.run.status;
    if (!status || TERMINAL.includes(status)) return;
    const timer = window.setInterval(() => void load(), 3000);
    return () => window.clearInterval(timer);
  }, [detail?.run.status, load]);

  // aria-live 播报：节点状态变化时播报一次
  useEffect(() => {
    const running = detail?.nodeRuns?.filter((n) => n.status === 'running').map((n) => n.nodeKey) || [];
    if (running.length > 0) {
      setAnnouncement(t('workflow.run.ariaRunning', { nodes: running.join(', ') }));
    } else if (detail?.run.status) {
      setAnnouncement(t('workflow.run.ariaStatus', { status: t(`workflow.nodeStatus.${detail.run.status}`) }));
    }
  }, [detail?.nodeRuns, detail?.run.status, t]);

  const statuses = useMemo(() => {
    const map: Record<string, string> = {};
    for (const nodeRun of detail?.nodeRuns || []) map[nodeRun.nodeKey] = nodeRun.status;
    return map;
  }, [detail?.nodeRuns]);

  const { nodes, edges } = useMemo(() => {
    if (!definition) return { nodes: [], edges: [] };
    const flow = definitionToFlow(definition);
    return {
      nodes: flow.nodes.map((node) => ({
        ...node,
        data: { ...node.data, status: statuses[node.id] || 'pending' },
      })),
      edges: flow.edges,
    };
  }, [definition, statuses]);

  const nodeColumns: ColumnsType<WorkflowNodeRun> = useMemo(
    () => [
      {
        title: t('workflow.run.col.nodeKey'),
        dataIndex: 'nodeKey',
        key: 'nodeKey',
        width: 160,
        render: (key: string) => <Typography.Text code>{key}</Typography.Text>,
      },
      {
        title: t('workflow.run.col.nodeType'),
        dataIndex: 'nodeType',
        key: 'nodeType',
        width: 120,
        render: (type: string) => t(`workflow.node.type.${type}`),
      },
      {
        title: t('common.status'),
        dataIndex: 'status',
        key: 'status',
        width: 120,
        render: (status: WorkflowNodeRun['status']) => {
          const mapped = nodeRunStatusMap[status] || nodeRunStatusMap.pending;
          return <Tag color={mapped.color}>{t(mapped.text)}</Tag>;
        },
      },
      { title: t('workflow.run.col.attempt'), dataIndex: 'attempt', key: 'attempt', width: 80 },
      {
        title: t('workflow.run.col.task'),
        dataIndex: 'taskId',
        key: 'taskId',
        width: 220,
        render: (taskId: string | null) =>
          taskId ? (
            <Typography.Link href={`/task?taskId=${taskId}`}>{taskId.slice(0, 12)}…</Typography.Link>
          ) : (
            '-'
          ),
      },
      {
        title: t('workflow.run.col.error'),
        dataIndex: 'errorMessage',
        key: 'errorMessage',
        ellipsis: true,
        render: (value: string | null) => (value ? <Text type="danger">{value}</Text> : '-'),
      },
      {
        title: t('common.createdAt'),
        dataIndex: 'createdAt',
        key: 'createdAt',
        width: 170,
        render: (value: string) => (value ? formatDateTime(value) : '-'),
      },
    ],
    [t]
  );

  if (loading && !detail) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 160 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (error) {
    return (
      <div style={{ padding: 16 }}>
        {error === '403' ? (
          <Alert type="error" showIcon message={t('workflow.error.403')} description={t('workflow.error.403Desc')} />
        ) : error === '404' ? (
          <Alert type="error" showIcon message={t('workflow.error.run404')} description={t('workflow.error.404Desc')} />
        ) : (
          <Alert type="error" showIcon message={t('workflow.error.generic')} description={error} />
        )}
      </div>
    );
  }

  if (!detail) return null;
  const run = detail.run;

  return (
    <div style={{ padding: 16 }}>
      <Card size="small" style={{ marginBottom: 12 }} styles={{ body: { padding: '12px 16px' } }}>
        <Row gutter={[12, 12]} align="middle">
          <Col flex="auto">
            <Space size={12} wrap>
              <Button icon={<ArrowLeftOutlined />} onClick={() => window.history.back()} />
              <Title level={5} style={{ margin: 0, color: token.colorText }}>
                {t('workflow.run.title')} · <Typography.Text code style={{ fontSize: 13 }}>{run.id}</Typography.Text>
              </Title>
              <Tag color={runStatusColor(run.status)}>{t(`workflow.nodeStatus.${run.status}`)}</Tag>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {t('workflow.run.col.version')}: v{run.versionNo}
              </Text>
            </Space>
          </Col>
          <Col>
            <Space>
              <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
                {t('common.refresh')}
              </Button>
              <Popconfirm
                title={t('workflow.run.cancelConfirm')}
                description={t('workflow.run.cancelHint')}
                okText={t('common.confirm')}
                cancelText={t('common.cancel')}
                okButtonProps={{ danger: true }}
                disabled={TERMINAL.includes(run.status)}
                onConfirm={async () => {
                  const res = await cancelWorkflowRun(run.id);
                  if (res.code === 200) {
                    message.success(t('workflow.run.cancelRequested'));
                    void load();
                  } else {
                    message.error(res.message || t('workflow.run.cancelFailed'));
                  }
                }}
              >
                <Button danger icon={<StopOutlined />} disabled={TERMINAL.includes(run.status)}>
                  {t('workflow.run.cancel')}
                </Button>
              </Popconfirm>
            </Space>
          </Col>
        </Row>
      </Card>

      <Row gutter={[12, 12]}>
        <Col flex="auto">
          <Card
            size="small"
            title={t('workflow.run.canvasTitle')}
            styles={{ body: { padding: 0, height: 460 } }}
          >
            {definition ? (
              <WorkflowCanvas
                nodes={nodes}
                edges={edges}
                onNodesChange={() => undefined}
                onEdgesChange={() => undefined}
                onConnect={() => undefined}
                connectionContext={{ nodes: definition.nodes, edges: definition.edges }}
                onDropNode={() => undefined}
                onMoveNodes={() => undefined}
                onRequestDelete={() => undefined}
                onCopyNodes={() => undefined}
                onPasteNodes={() => undefined}
                onDuplicateNodes={() => undefined}
                onSelectionChange={() => undefined}
                onOpenNodeConfig={() => undefined}
                onEditEdge={() => undefined}
                onUndo={() => undefined}
                onRedo={() => undefined}
                onSave={() => undefined}
                readOnly
                statuses={statuses}
              />
            ) : (
              <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 120 }}>
                <Spin />
              </div>
            )}
          </Card>
        </Col>
      </Row>

      {/* 可访问性：运行态播报（aria-live polite） */}
      <div role="status" aria-live="polite" className="wf-sr-only" data-testid="run-announcer">
        {announcement}
      </div>

      <Row gutter={[12, 12]} style={{ marginTop: 12 }}>
        <Col xs={24} lg={12}>
          <Card size="small" title={t('workflow.run.nodes')} styles={{ body: { padding: 0 } }}>
            <Table<WorkflowNodeRun>
              columns={nodeColumns}
              dataSource={detail.nodeRuns || []}
              rowKey="id"
              size="small"
              pagination={false}
              scroll={{ x: 900 }}
              expandable={{
                expandedRowRender: (record) => (
                  <Space direction="vertical" size={4} style={{ width: '100%' }}>
                    {record.input && (
                      <div>
                        <Text type="secondary">input: </Text>
                        <pre className="wf-inline-json">{JSON.stringify(record.input, null, 2)}</pre>
                      </div>
                    )}
                    {record.output && (
                      <div>
                        <Text type="secondary">output: </Text>
                        <pre className="wf-inline-json">{JSON.stringify(record.output, null, 2)}</pre>
                      </div>
                    )}
                  </Space>
                ),
              }}
            />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Card size="small" title={t('workflow.run.info')}>
              <Descriptions size="small" column={1}>
                <Descriptions.Item label={t('workflow.run.col.trigger')}>
                  {t(`workflow.run.trigger.${run.triggerType}`)}
                </Descriptions.Item>
                <Descriptions.Item label={t('workflow.run.input')}>
                  <pre className="wf-inline-json">{JSON.stringify(run.input || {}, null, 2)}</pre>
                </Descriptions.Item>
                <Descriptions.Item label={t('workflow.run.variables')}>
                  {run.variablesTruncated && <Tag color="orange">{t('workflow.run.variablesTruncated')}</Tag>}
                  <pre className="wf-inline-json">{JSON.stringify(run.variables || {}, null, 2)}</pre>
                </Descriptions.Item>
                <Descriptions.Item label={t('workflow.run.output')}>
                  {run.output ? (
                    <pre className="wf-inline-json">{JSON.stringify(run.output, null, 2)}</pre>
                  ) : (
                    '-'
                  )}
                </Descriptions.Item>
                {run.errorMessage && (
                  <Descriptions.Item label={t('workflow.run.col.error')}>
                    <Text type="danger">{run.errorMessage}</Text>
                  </Descriptions.Item>
                )}
              </Descriptions>
            </Card>
            <Card size="small" title={t('workflow.run.logs')}>
              <RunLogPanel runId={run.id} active={!TERMINAL.includes(run.status)} />
            </Card>
          </Space>
        </Col>
      </Row>
    </div>
  );
};

function runStatusColor(status: string): string {
  const mapped = { pending: 'default', running: 'processing', succeeded: 'success', failed: 'error', cancelled: 'default', timeout: 'warning', waiting: 'purple', queued: 'default', interrupted: 'warning' } as Record<string, string>;
  return mapped[status] || 'default';
}

export default WorkflowRunMonitorPage;
