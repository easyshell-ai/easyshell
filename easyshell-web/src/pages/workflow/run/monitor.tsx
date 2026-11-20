import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Popconfirm,
  Row,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import { ArrowLeftOutlined, ReloadOutlined, StopOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { theme } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { cancelWorkflowRun, getWorkflowRun, getWorkflowVersion, testRunNode } from '../api';
import type { WorkflowDefinition, WorkflowNodeRun, WorkflowRunDetail, WorkflowTestRun } from '../types';
import { definitionToFlow } from '../graph';
import { nodeRunStatusMap } from '../status';
import { formatDateTime } from '../../../utils/format';
import WorkflowCanvas from '../editor/canvas/WorkflowCanvas';
import RunLogPanel from './RunLogPanel';
import '../workflow.css';

const { Title, Text } = Typography;

const TERMINAL = ['succeeded', 'failed', 'cancelled', 'timeout', 'interrupted'];

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
  const [definitionUnavailable, setDefinitionUnavailable] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const [selectedNodeKey, setSelectedNodeKey] = useState<string | null>(null);
  const [onlyFailed, setOnlyFailed] = useState(false);
  const [testRunning, setTestRunning] = useState(false);
  const [testResult, setTestResult] = useState<WorkflowTestRun | null>(null);
  const [testError, setTestError] = useState<string | null>(null);
  const generation = useRef(0);

  const load = useCallback(async () => {
    if (!runId) return;
    const requestGeneration = ++generation.current;
    setLoading(true);
    setError(null);
    try {
      const res = await getWorkflowRun(runId);
      if (requestGeneration !== generation.current) return;
      if (res.code === 200 && res.data) {
        setDetail(res.data);
      } else {
        setDetail(null);
        setDefinition(null);
        setSelectedNodeKey(null);
        setTestResult(null);
        setTestError(null);
        setError(res.code === 404 ? '404' : res.code === 403 ? '403' : 'unavailable');
      }
    } catch {
      if (requestGeneration !== generation.current) return;
      setDetail(null);
      setDefinition(null);
      setSelectedNodeKey(null);
      setTestResult(null);
      setTestError(null);
      setError('unavailable');
    } finally {
      if (requestGeneration === generation.current) setLoading(false);
    }
  }, [runId]);

  useEffect(() => {
    void load();
    return () => { generation.current++; };
  }, [load]);

  // 画布要画边，需要不可变版本的 definition：run.workflowId + run.versionId 反查
  useEffect(() => {
    const run = detail?.run;
    setDefinition(null);
    setDefinitionUnavailable(false);
    if (!run?.workflowId || !run?.versionId) return;
    let cancelled = false;
    getWorkflowVersion(run.workflowId, run.versionId).then((res) => {
      if (!cancelled) {
        if (res.code === 200 && res.data) setDefinition(res.data.definition);
        else setDefinitionUnavailable(true);
      }
    }).catch(() => { if (!cancelled) setDefinitionUnavailable(true); });
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
        selected: node.id === selectedNodeKey,
        data: { ...node.data, status: statuses[node.id] || 'pending' },
      })),
      edges: flow.edges,
    };
  }, [definition, statuses, selectedNodeKey]);

  const selectedNodeRun = useMemo(
    () => (detail?.nodeRuns || []).find((nodeRun) => nodeRun.nodeKey === selectedNodeKey) || null,
    [detail?.nodeRuns, selectedNodeKey]
  );

  const visibleNodeRuns = useMemo(
    () => (detail?.nodeRuns || []).filter((nodeRun) => !onlyFailed || nodeRun.status === 'failed'),
    [detail?.nodeRuns, onlyFailed]
  );

  const runNodeTest = useCallback(async () => {
    if (!detail?.run.id || !selectedNodeRun) return;
    setTestRunning(true);
    setTestError(null);
    setTestResult(null);
    const requestGeneration = generation.current;
    try {
      const res = await testRunNode(detail.run.id, selectedNodeRun.nodeKey);
      if (requestGeneration !== generation.current) return;
      if (res.code === 200 && res.data) {
        setTestResult(res.data);
      } else {
        setTestError(res.code === 403 ? t('workflow.run.detailUnavailable') : (res.message || t('workflow.run.testRunFailed')));
      }
    } catch {
      if (requestGeneration === generation.current) setTestError(t('workflow.run.testRunFailed'));
    } finally {
      setTestRunning(false);
    }
  }, [detail, selectedNodeRun, t]);

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

  if ((loading && !detail) || (detail && detail.run.id !== runId)) {
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
          <Alert type="error" showIcon message={t('workflow.run.detailUnavailable')} />
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
            ) : definitionUnavailable ? (
              <Alert type="warning" showIcon message={t('workflow.run.detailUnavailable')} />
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
          <Card
            size="small"
            title={t('workflow.run.nodes')}
            styles={{ body: { padding: 0 } }}
            extra={
              <Select
                size="small"
                value={onlyFailed ? 'failed' : 'all'}
                onChange={(value) => setOnlyFailed(value === 'failed')}
                options={[
                  { value: 'all', label: t('workflow.run.filter.all') },
                  { value: 'failed', label: t('workflow.run.filter.failed') },
                ]}
                aria-label={t('workflow.run.filter.label')}
                style={{ width: 130 }}
              />
            }
          >
            <Table<WorkflowNodeRun>
              columns={nodeColumns}
              dataSource={visibleNodeRuns}
              rowKey="id"
              size="small"
              pagination={false}
              scroll={{ x: 900 }}
              onRow={(record) => ({
                onClick: () => {
                  setSelectedNodeKey(record.nodeKey);
                  setTestResult(null);
                  setTestError(null);
                },
                style: { cursor: 'pointer' },
              })}
              locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('workflow.run.filter.empty')} /> }}
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
                  {run.input == null ? t('workflow.run.dataUnavailable') : <pre className="wf-inline-json">{JSON.stringify(run.input, null, 2)}</pre>}
                </Descriptions.Item>
                <Descriptions.Item label={t('workflow.run.variables')}>
                  {run.variablesTruncated && <Tag color="orange">{t('workflow.run.variablesTruncated')}</Tag>}
                  {run.variables == null ? t('workflow.run.dataUnavailable') : <pre className="wf-inline-json">{JSON.stringify(run.variables, null, 2)}</pre>}
                </Descriptions.Item>
                <Descriptions.Item label={t('workflow.run.output')}>
                  {run.output ? (
                    <pre className="wf-inline-json">{JSON.stringify(run.output, null, 2)}</pre>
                  ) : (
                    t('workflow.run.dataUnavailable')
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

      <Drawer
        title={t('workflow.run.inspector.title')}
        open={!!selectedNodeRun}
        onClose={() => setSelectedNodeKey(null)}
        width={520}
        styles={{ body: { padding: 0 } }}
      >
        {selectedNodeRun && (
          <div className="wf-run-inspector" data-testid="run-node-inspector">
            <Descriptions size="small" column={1} bordered>
              <Descriptions.Item label={t('workflow.run.col.nodeKey')}>
                <Text code>{selectedNodeRun.nodeKey}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('workflow.run.col.nodeType')}>
                {t(`workflow.node.type.${selectedNodeRun.nodeType}`)}
              </Descriptions.Item>
              <Descriptions.Item label={t('common.status')}>
                <Tag color={nodeRunStatusMap[selectedNodeRun.status]?.color}>
                  {t(nodeRunStatusMap[selectedNodeRun.status]?.text || 'workflow.nodeStatus.pending')}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('workflow.run.col.attempt')}>{selectedNodeRun.attempt}</Descriptions.Item>
              <Descriptions.Item label={t('workflow.run.col.task')}>
                {selectedNodeRun.taskId ? (
                  <Typography.Link href={`/task?taskId=${selectedNodeRun.taskId}`}>{selectedNodeRun.taskId}</Typography.Link>
                ) : (
                  '-'
                )}
              </Descriptions.Item>
              <Descriptions.Item label={t('workflow.run.inspector.input')}>
                {selectedNodeRun.input == null ? t('workflow.run.dataUnavailable') : <pre className="wf-inline-json">{JSON.stringify(selectedNodeRun.input, null, 2)}</pre>}
              </Descriptions.Item>
              <Descriptions.Item label={t('workflow.run.inspector.output')}>
                {selectedNodeRun.output == null ? t('workflow.run.dataUnavailable') : <pre className="wf-inline-json">{JSON.stringify(selectedNodeRun.output, null, 2)}</pre>}
              </Descriptions.Item>
              <Descriptions.Item label={t('workflow.run.col.error')}>
                {selectedNodeRun.errorMessage ? <Text type="danger">{selectedNodeRun.errorMessage}</Text> : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('workflow.run.inspector.warning')}>
                {selectedNodeRun.warningMessage ? <Text type="warning">{selectedNodeRun.warningMessage}</Text> : '-'}
              </Descriptions.Item>
            </Descriptions>

            <div className="wf-run-test">
              <Space>
                <Button
                  type="primary"
                  icon={<ThunderboltOutlined />}
                  loading={testRunning}
                  onClick={() => void runNodeTest()}
                  data-testid="run-node-test"
                >
                  {t('workflow.run.inspector.testRun')}
                </Button>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {t('workflow.run.inspector.testRunHint')}
                </Text>
              </Space>

              {testError && <Alert style={{ marginTop: 10 }} type="error" showIcon message={testError} />}

              {testResult && (
                <div className="wf-test-result" data-testid="run-node-test-result">
                  <Descriptions size="small" column={1} bordered title={t('workflow.run.inspector.testResult')}>
                    <Descriptions.Item label={t('workflow.run.inspector.testStatus')}>
                      <Tag color={testResult.status === 'ok' ? 'green' : 'red'}>{testResult.status}</Tag>
                    </Descriptions.Item>
                    <Descriptions.Item label={t('workflow.run.inspector.riskLevel')}>
                      {testResult.riskLevel || '-'}
                    </Descriptions.Item>
                    <Descriptions.Item label={t('workflow.run.inspector.riskPolicy')}>
                      {testResult.riskPolicy || '-'}
                    </Descriptions.Item>
                    <Descriptions.Item label={t('workflow.run.inspector.conditionResult')}>
                      {testResult.conditionResult == null
                        ? '-'
                        : testResult.conditionResult
                          ? t('common.yes')
                          : t('common.no')}
                    </Descriptions.Item>
                    <Descriptions.Item label={t('workflow.run.inspector.targetCount')}>
                      {testResult.targetAgentCount ?? '-'}
                    </Descriptions.Item>
                    <Descriptions.Item label={t('workflow.run.inspector.renderedScript')}>
                      {testResult.renderedScript ? (
                        <pre className="wf-inline-json">{testResult.renderedScript}</pre>
                      ) : (
                        '-'
                      )}
                    </Descriptions.Item>
                    <Descriptions.Item label={t('workflow.run.inspector.warnings')}>
                      {testResult.warnings && testResult.warnings.length > 0 ? (
                        <Space direction="vertical" size={2}>
                          {testResult.warnings.map((warning, index) => (
                            <Text key={index} type="warning">
                              {warning}
                            </Text>
                          ))}
                        </Space>
                      ) : (
                        '-'
                      )}
                    </Descriptions.Item>
                  </Descriptions>
                </div>
              )}
            </div>
          </div>
        )}
      </Drawer>
    </div>
  );
};

function runStatusColor(status: string): string {
  const mapped = { pending: 'default', running: 'processing', succeeded: 'success', failed: 'error', cancelled: 'default', timeout: 'warning', waiting: 'purple', queued: 'default', interrupted: 'warning' } as Record<string, string>;
  return mapped[status] || 'default';
}

export default WorkflowRunMonitorPage;
