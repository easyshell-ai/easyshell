import { useEffect, useState, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Table, Button, Form, Input, Select, InputNumber, Tag, Space, Drawer, Card,
  Row, Col, message, Divider, Descriptions, theme,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { PlayCircleOutlined, EyeOutlined, ClusterOutlined, TagsOutlined, ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { createTask, getTaskList, getTaskDetail } from '../../api/task';
import { getScriptList } from '../../api/script';
import { getHostList } from '../../api/host';
import { getClusterList } from '../../api/cluster';
import { getTagList } from '../../api/tag';
import type { Task, TaskDetail, Script, Agent, Job, TaskCreateRequest, ClusterVO, TagVO } from '../../types';
import { taskStatusMap, jobStatusMap } from '../../utils/status';

const { TextArea } = Input;

const TaskPage: React.FC = () => {
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [scripts, setScripts] = useState<Script[]>([]);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [clusters, setClusters] = useState<ClusterVO[]>([]);
  const [tags, setTags] = useState<TagVO[]>([]);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [taskDetail, setTaskDetail] = useState<TaskDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [logs, setLogs] = useState<string[]>([]);
  const [wsStatus, setWsStatus] = useState<'idle' | 'connected' | 'disconnected'>('idle');
  const [hasRealLogs, setHasRealLogs] = useState(false);
  const [scriptMode, setScriptMode] = useState<'select' | 'manual'>('select');
  const [targetMode, setTargetMode] = useState<'agent' | 'cluster' | 'tag'>('agent');
  const [form] = Form.useForm<TaskCreateRequest & { scriptContentManual?: string }>();
  const wsRef = useRef<WebSocket | null>(null);
  const logContainerRef = useRef<HTMLDivElement>(null);
  const detailTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const refreshTaskDetail = useCallback((taskId: string) => {
    getTaskDetail(taskId).then((res) => {
      if (res.code === 200) {
        setTaskDetail(res.data);
        const status = res.data?.task?.status;
        if (status !== undefined && status !== 0 && status !== 1) {
          if (detailTimerRef.current) {
            clearInterval(detailTimerRef.current);
            detailTimerRef.current = null;
          }
        }
      }
    });
  }, []);

  const fetchTasks = useCallback(() => {
    setLoading(true);
    getTaskList()
      .then((res) => {
        if (res.code === 200) setTasks(res.data || []);
      })
      .finally(() => setLoading(false));
  }, []);

  const fetchOptions = useCallback(() => {
    getScriptList().then((res) => {
      if (res.code === 200) setScripts(res.data || []);
    });
    getHostList().then((res) => {
      if (res.code === 200) setAgents(res.data || []);
    });
    getClusterList().then((res) => {
      if (res.code === 200) setClusters(res.data || []);
    });
    getTagList().then((res) => {
      if (res.code === 200) setTags(res.data || []);
    });
  }, []);

  useEffect(() => {
    fetchTasks();
    fetchOptions();
  }, [fetchTasks, fetchOptions]);

  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
    }
  }, [logs]);

  useEffect(() => {
    return () => {
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
      if (detailTimerRef.current) {
        clearInterval(detailTimerRef.current);
        detailTimerRef.current = null;
      }
    };
  }, []);

  const connectWebSocket = (taskId: string) => {
    if (wsRef.current) wsRef.current.close();
    setLogs([]);
    setHasRealLogs(false);
    setWsStatus('idle');

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/task/log/${taskId}`;
    const ws = new WebSocket(wsUrl);

    ws.onopen = () => setWsStatus('connected');
    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        const line = `[${data.jobId?.slice(0, 8) || 'unknown'}] ${data.log}`;
        setHasRealLogs(true);
        setLogs((prev) => [...prev, line]);
      } catch {
        setHasRealLogs(true);
        setLogs((prev) => [...prev, event.data]);
      }
    };
    ws.onclose = () => setWsStatus('disconnected');
    ws.onerror = () => setWsStatus('disconnected');

    wsRef.current = ws;
  };

  const handleViewDetail = (taskId: string) => {
    setDrawerOpen(true);
    setDetailLoading(true);
    setTaskDetail(null);
    getTaskDetail(taskId)
      .then((res) => {
        if (res.code === 200) setTaskDetail(res.data);
      })
      .finally(() => setDetailLoading(false));
    connectWebSocket(taskId);
    if (detailTimerRef.current) clearInterval(detailTimerRef.current);
    detailTimerRef.current = setInterval(() => refreshTaskDetail(taskId), 2000);
  };

  const handleCloseDrawer = () => {
    setDrawerOpen(false);
    setTaskDetail(null);
    setLogs([]);
    setHasRealLogs(false);
    setWsStatus('idle');
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }
    if (detailTimerRef.current) {
      clearInterval(detailTimerRef.current);
      detailTimerRef.current = null;
    }
  };

  const handleSubmit = () => {
    form.validateFields().then((values) => {
      setSubmitting(true);

      const request: TaskCreateRequest = {
        name: values.name,
        timeoutSeconds: values.timeoutSeconds || 600,
      };

      if (targetMode === 'agent') {
        request.agentIds = values.agentIds;
      } else if (targetMode === 'cluster') {
        request.clusterIds = values.clusterIds;
      } else if (targetMode === 'tag') {
        request.tagIds = values.tagIds;
      }

      if (scriptMode === 'select' && values.scriptId) {
        request.scriptId = values.scriptId;
      } else if (scriptMode === 'manual' && values.scriptContentManual) {
        request.scriptContent = values.scriptContentManual;
      }

      createTask(request)
        .then((res) => {
          if (res.code === 200) {
            message.success(t('task.taskCreated'));
            form.resetFields();
            fetchTasks();
            if (res.data?.id) {
              setDrawerOpen(true);
              setDetailLoading(true);
              setTaskDetail(null);
              connectWebSocket(res.data.id);
              setTimeout(() => {
                getTaskDetail(res.data.id)
                  .then((detailRes) => {
                    if (detailRes.code === 200) setTaskDetail(detailRes.data);
                  })
                  .finally(() => setDetailLoading(false));
              }, 1500);
            }
          } else {
            message.error(res.message || t('task.createFailed'));
          }
        })
        .finally(() => setSubmitting(false));
    });
  };

  const onlineAgents = agents.filter((a) => a.status === 1);

  const taskColumns: ColumnsType<Task> = [
    { title: t('task.col.name'), dataIndex: 'name', key: 'name', width: 200 },
    {
      title: t('task.col.status'), dataIndex: 'status', key: 'status', width: 100,
      render: (status: number) => {
        const s = taskStatusMap[status] || taskStatusMap[0];
        return <Tag color={s.color}>{t(s.text)}</Tag>;
      },
    },
    {
      title: t('task.col.progress'), key: 'progress', width: 140,
      render: (_, record) => (
        <span>
          <Tag color="green">{record.successCount}</Tag>
          {record.failedCount > 0 && <Tag color="red">{record.failedCount}</Tag>}
          <span style={{ color: token.colorTextTertiary }}>/ {record.totalCount}</span>
        </span>
      ),
    },
    { title: t('task.col.timeout'), dataIndex: 'timeoutSeconds', key: 'timeoutSeconds', width: 100 },
    { title: t('task.col.createdBy'), dataIndex: 'createdBy', key: 'createdBy', width: 100 },
    {
      title: t('task.col.createdAt'), dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (val: string) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
    {
      title: t('task.col.actions'), key: 'action', width: 100, fixed: 'right',
      render: (_, record) => (
        <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => handleViewDetail(record.id)}>
          {t('task.detail')}
        </Button>
      ),
    },
  ];

  const jobColumns: ColumnsType<Job> = [
    { title: t('task.job.agentId'), dataIndex: 'agentId', key: 'agentId', width: 200, ellipsis: true },
    {
      title: t('task.col.status'), dataIndex: 'status', key: 'status', width: 100,
      render: (status: number) => {
        const s = jobStatusMap[status] || jobStatusMap[0];
        return <Tag color={s.color}>{t(s.text)}</Tag>;
      },
    },
    {
      title: t('task.job.exitCode'), dataIndex: 'exitCode', key: 'exitCode', width: 80,
      render: (val: number) => (val !== null && val !== undefined ? val : '-'),
    },
    {
      title: t('task.job.startedAt'), dataIndex: 'startedAt', key: 'startedAt', width: 170,
      render: (val: string) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
    {
      title: t('task.job.finishedAt'), dataIndex: 'finishedAt', key: 'finishedAt', width: 170,
      render: (val: string) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
  ];

  return (
    <>
      <Card title={<span style={{ color: token.colorText }}>{t('task.createTask')}</span>} size="small" style={{ marginBottom: 16, borderRadius: 12 }}>
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item name="name" label={t('task.col.name')} rules={[{ required: true, message: t('task.pleaseInputName') }]}>
                <Input placeholder={t('task.pleaseInputName')} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label={t('task.targetMode')}>
                <Space>
                  <Button type={targetMode === 'agent' ? 'primary' : 'default'} size="small"
                    onClick={() => setTargetMode('agent')}>
                    {t('task.byHost')}
                  </Button>
                  <Button type={targetMode === 'cluster' ? 'primary' : 'default'} size="small"
                    icon={<ClusterOutlined />} onClick={() => setTargetMode('cluster')}>
                    {t('task.byCluster')}
                  </Button>
                  <Button type={targetMode === 'tag' ? 'primary' : 'default'} size="small"
                    icon={<TagsOutlined />} onClick={() => setTargetMode('tag')}>
                    {t('task.byTag')}
                  </Button>
                </Space>
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="timeoutSeconds" label={t('task.col.timeout')} initialValue={600}>
                <InputNumber min={10} max={86400} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={24}>
              {targetMode === 'agent' && (
                <Form.Item name="agentIds" label={t('task.targetHost')} rules={[{ required: true, message: t('task.pleaseSelectHost') }]}>
                  <Select
                    mode="multiple"
                    placeholder={t('task.pleaseSelectOnlineHost')}
                    options={onlineAgents.map((a) => ({
                      value: a.id,
                      label: `${a.hostname} (${a.ip})`,
                    }))}
                    maxTagCount={3}
                  />
                </Form.Item>
              )}
              {targetMode === 'cluster' && (
                <Form.Item name="clusterIds" label={t('task.targetCluster')}
                  rules={[{ required: true, message: t('task.pleaseSelectCluster') }]}>
                  <Select
                    mode="multiple"
                    placeholder={t('task.clusterPlaceholder')}
                    options={clusters.map((c) => ({
                      value: c.id,
                      label: `${c.name} (${c.agentCount} ${t('task.hosts')})`,
                    }))}
                    maxTagCount={3}
                  />
                </Form.Item>
              )}
              {targetMode === 'tag' && (
                <Form.Item name="tagIds" label={t('task.targetTag')}
                  rules={[{ required: true, message: t('task.pleaseSelectTag') }]}>
                  <Select
                    mode="multiple"
                    placeholder={t('task.tagPlaceholder')}
                    options={tags.map((tag) => ({
                      value: tag.id,
                      label: `${tag.name} (${tag.agentCount} ${t('task.hosts')})`,
                    }))}
                    maxTagCount={3}
                  />
                </Form.Item>
              )}
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={24}>
              <Form.Item label={t('task.scriptSource')}>
                <Space>
                  <Button type={scriptMode === 'select' ? 'primary' : 'default'} size="small"
                    onClick={() => setScriptMode('select')}>
                    {t('task.selectScript')}
                  </Button>
                  <Button type={scriptMode === 'manual' ? 'primary' : 'default'} size="small"
                    onClick={() => setScriptMode('manual')}>
                    {t('task.manualInput')}
                  </Button>
                </Space>
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={24}>
              {scriptMode === 'select' ? (
                <Form.Item name="scriptId" label={t('task.selectScript')} rules={[{ required: true, message: t('task.pleaseSelectScript') }]}>
                  <Select
                    placeholder={t('task.pleaseSelectExistingScript')}
                    showSearch
                    optionFilterProp="label"
                    options={scripts.map((s) => ({
                      value: s.id,
                      label: `${s.name} (${s.scriptType})`,
                    }))}
                  />
                </Form.Item>
              ) : (
                <Form.Item name="scriptContentManual" label={t('task.scriptContent')} rules={[{ required: true, message: t('task.pleaseInputScript') }]}>
                  <TextArea rows={6} placeholder={"#!/bin/bash\necho 'Hello EasyShell'"} style={{ fontFamily: 'monospace', background: token.colorFillAlter, color: token.colorText, border: `1px solid ${token.colorBorderSecondary}` }} />
                </Form.Item>
              )}
            </Col>
          </Row>
          <Button type="primary" icon={<PlayCircleOutlined />} loading={submitting} onClick={handleSubmit}>
            {t('task.executeTask')}
          </Button>
        </Form>
      </Card>

      <Card title={<span style={{ color: token.colorText }}>{t('task.taskList')}</span>} size="small" style={{ borderRadius: 12 }}>
        <Table<Task>
          columns={taskColumns}
          dataSource={tasks}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1000 }}
          pagination={{ pageSize: 15 }}
        />
      </Card>

      <Drawer
        title={
          <Space>
            <span>{t('task.taskDetail')}</span>
            {taskDetail && (
              <Button type="link" size="small" icon={<ReloadOutlined />}
                onClick={() => refreshTaskDetail(taskDetail.task.id)}>
                {t('task.refresh')}
              </Button>
            )}
          </Space>
        }
        placement="right"
        width={800}
        open={drawerOpen}
        onClose={handleCloseDrawer}
        destroyOnClose
      >
        {taskDetail && (
          <>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('task.col.name')}>{taskDetail.task.name}</Descriptions.Item>
              <Descriptions.Item label={t('task.col.status')}>
                <Tag color={taskStatusMap[taskDetail.task.status]?.color}>
                  {t(taskStatusMap[taskDetail.task.status]?.text || 'common.unknown')}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('task.successFailedTotal')}>
                {taskDetail.task.successCount} / {taskDetail.task.failedCount} / {taskDetail.task.totalCount}
              </Descriptions.Item>
              <Descriptions.Item label={t('task.col.createdAt')}>
                {taskDetail.task.createdAt ? dayjs(taskDetail.task.createdAt).format('YYYY-MM-DD HH:mm:ss') : '-'}
              </Descriptions.Item>
            </Descriptions>

            <Divider style={{ marginTop: 24 }}>{t('task.executionNodes')}</Divider>
            <Table<Job>
              columns={jobColumns}
              dataSource={taskDetail.jobs || []}
              rowKey="id"
              size="small"
              pagination={false}
              loading={detailLoading}
              expandable={{
                expandedRowRender: (record) => (
                  <pre style={{
                    background: token.colorFillAlter, padding: 12, borderRadius: 6,
                    maxHeight: 200, overflow: 'auto', fontSize: 12, margin: 0,
                    whiteSpace: 'pre-wrap', wordBreak: 'break-all', color: token.colorText,
                  }}>
                    {record.output || t('task.noOutput')}
                  </pre>
                ),
              }}
            />

            <Divider style={{ marginTop: 24 }}>{t('task.realTimeLogs')}</Divider>
            <div
              ref={logContainerRef}
              style={{
                background: token.colorFillAlter,
                color: token.colorText,
                fontFamily: "'Cascadia Code', 'Fira Code', 'Consolas', monospace",
                fontSize: 13,
                lineHeight: '1.6',
                padding: 16,
                borderRadius: 8,
                height: 360,
                overflowY: 'auto',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-all',
              }}
            >
              {(() => {
                const jobOutputs = (taskDetail.jobs || [])
                  .filter((j) => j.output)
                  .map((j) => `[${j.agentId?.slice(0, 12) || 'agent'}] ${j.output}`);

                // Show real-time WS logs if we received actual log data
                // Otherwise fall back to job outputs from the API
                const displayLogs: string[] = [];

                if (wsStatus === 'connected') {
                  displayLogs.push(`[${t('task.system')}] ${t('task.logConnected')}`);
                }

                if (hasRealLogs && logs.length > 0) {
                  displayLogs.push(...logs);
                } else if (jobOutputs.length > 0) {
                  displayLogs.push(...jobOutputs);
                }

                if (wsStatus === 'disconnected') {
                  displayLogs.push(`[${t('task.system')}] ${t('task.logDisconnected')}`);
                }

                if (displayLogs.length === 0) {
                  return <span style={{ color: token.colorTextTertiary }}>{t('task.waitingLogs')}</span>;
                }
                return displayLogs.map((line, i) => <div key={i}>{line}</div>);
              })()}
            </div>
          </>
        )}
        {!taskDetail && detailLoading && (
          <div style={{ textAlign: 'center', padding: 40, color: token.colorTextTertiary }}>{t('common.loading')}</div>
        )}
      </Drawer>
    </>
  );
};

export default TaskPage;
