import { useEffect, useState, useCallback, useRef, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Table, Button, Form, Input, Select, InputNumber, Tag, Space, Drawer, Card,
  Row, Col, message, Divider, Descriptions, theme, Modal, Alert, Spin,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { PlayCircleOutlined, EyeOutlined, ClusterOutlined, TagsOutlined, ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import CodeMirror from '@uiw/react-codemirror';
import { StreamLanguage } from '@codemirror/language';
import { shell } from '@codemirror/legacy-modes/mode/shell';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { getWorkflowPage, getWorkflowDetail, getWorkflowVersion, triggerWorkflowRun } from '../workflow/api';
import { TriggerModal } from '../workflow/editor/modals';
import type { WorkflowDefinition, WorkflowSummary } from '../workflow/types';
import { createTask, getTaskList, getTaskDetail, submitTaskForApproval } from '../../api/task';
import { getToken } from '../../api/request';
import { buildTaskLogWsUrl } from '../../utils/ws';
import { getScriptList, getScriptParameters } from '../../api/script';
import { getHostList } from '../../api/host';
import { queryMyHostPermissions } from '../../api/myHostPermissions';
import { getClusterList } from '../../api/cluster';
import { getTagList } from '../../api/tag';
import type { Task, TaskDetail, Script, Agent, Job, TaskCreateRequest, ClusterVO, TagVO, RiskAssessment } from '../../types';
import { taskStatusMap, jobStatusMap } from '../../utils/status';

/**
 * Cap on the real-time log buffer: a long-running task can stream tens of thousands
 * of lines, and an unbounded array grows browser memory until the tab dies. Only the
 * tail is kept, which is also the only part the log pane ever shows.
 */
const MAX_TASK_LOG_LINES = 5000;

const TaskPage: React.FC = () => {
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const taskIdFromUrl = searchParams.get('taskId');
  const [workflowPickerOpen, setWorkflowPickerOpen] = useState(false);
  const [workflowLoading, setWorkflowLoading] = useState(false);
  const [workflowPage, setWorkflowPage] = useState({ content: [] as WorkflowSummary[], number: 0, totalElements: 0, size: 20 });
  const [triggerTarget, setTriggerTarget] = useState<{ id: number; name: string; versionId: number; version: number; definition: WorkflowDefinition } | null>(null);
  const [triggerLoading, setTriggerLoading] = useState(false);
  const workflowRequestRef = useRef(0);
  const detailRequestRef = useRef(0);
  const taskListRequestRef = useRef(0);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(true);
  const [taskListError, setTaskListError] = useState<'identity' | 'fetch' | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [scripts, setScripts] = useState<Script[]>([]);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [hostPermissions, setHostPermissions] = useState<Record<string, boolean>>({});
  const [permissionStatus, setPermissionStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const permissionRequestRef = useRef(0);
  const permissionSnapshotRef = useRef<{ status: 'loading' | 'ready' | 'error'; values: Record<string, boolean> }>({ status: 'loading', values: {} });
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
  const targetModeRef = useRef(targetMode);
  const changeTargetMode = (mode: 'agent' | 'cluster' | 'tag') => {
    targetModeRef.current = mode;
    setTargetMode(mode);
  };
  const [form] = Form.useForm<TaskCreateRequest & { scriptContentManual?: string }>();
  const wsRef = useRef<WebSocket | null>(null);
  const logContainerRef = useRef<HTMLDivElement>(null);
  const detailTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [manualScriptContent, setManualScriptContent] = useState('');
  const [taskEditorHeight, setTaskEditorHeight] = useState<number>(240);
  const taskEditorDraggingRef = useRef(false);
  const taskEditorContainerRef = useRef<HTMLDivElement>(null);
  const [scriptParameters, setScriptParameters] = useState<string[]>([]);
  const [parameterValues, setParameterValues] = useState<Record<string, string>>({});

  // Determine dark mode from theme token
  const isDark = token.colorBgContainer !== '#ffffff' && token.colorBgBase !== '#ffffff';

  // CodeMirror extensions for shell highlighting
  const editorExtensions = useMemo(() => [StreamLanguage.define(shell)], []);

  // Vertical resize handler for task script editor
  const handleTaskEditorVResizeDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    taskEditorDraggingRef.current = true;
    const currentH = taskEditorContainerRef.current?.getBoundingClientRect().height || 240;
    const startY = e.clientY;
    const startH = currentH;
    const onMouseMove = (ev: MouseEvent) => {
      if (!taskEditorDraggingRef.current) return;
      const dy = ev.clientY - startY;
      const newH = Math.max(120, startH + dy);
      setTaskEditorHeight(newH);
    };
    const onMouseUp = () => {
      taskEditorDraggingRef.current = false;
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.body.style.cursor = 'row-resize';
    document.body.style.userSelect = 'none';
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
  }, []);

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
    const requestId = ++taskListRequestRef.current;
    setLoading(true);
    setTaskListError(null);
    getTaskList()
      .then((res) => {
        if (requestId !== taskListRequestRef.current) return;
        if (res.code === 200) setTasks(res.data || []);
        else setTaskListError(res.code === 503 ? 'identity' : 'fetch');
      })
      .catch((error: unknown) => {
        if (requestId !== taskListRequestRef.current) return;
        const code = (error as { response?: { data?: { code?: number } } })?.response?.data?.code;
        setTaskListError(code === 503 ? 'identity' : 'fetch');
      })
      .finally(() => { if (requestId === taskListRequestRef.current) setLoading(false); });
  }, []);

  const loadHostPermissions = useCallback(async (hosts: Agent[]) => {
    const requestId = ++permissionRequestRef.current;
    permissionSnapshotRef.current = { status: 'loading', values: {} };
    setPermissionStatus('loading');
    setHostPermissions({});
    try {
      const ids = hosts.filter((host) => host.status === 1).map((host) => host.id);
      const batches: (typeof ids)[] = [];
      for (let i = 0; i < ids.length; i += 100) batches.push(ids.slice(i, i + 100));
      const responses = await Promise.all(batches.map((batch) => queryMyHostPermissions(batch)));
      if (responses.some((res) => res.code !== 200 || !Array.isArray(res.data))) throw new Error('Permission query failed');
      const values: Record<string, boolean> = {};
      responses.forEach((res, index) => res.data.forEach((permission) => {
        if (batches[index].includes(permission.agentId) && typeof permission.execute === 'boolean') values[permission.agentId] = permission.execute;
      }));
      if (requestId !== permissionRequestRef.current) return;
      permissionSnapshotRef.current = { status: 'ready', values };
      setHostPermissions(values);
      setPermissionStatus('ready');
    } catch {
      if (requestId !== permissionRequestRef.current) return;
      permissionSnapshotRef.current = { status: 'error', values: {} };
      setPermissionStatus('error');
    }
  }, []);

  const fetchOptions = useCallback(() => {
    getScriptList().then((res) => {
      if (res.code === 200) setScripts(res.data || []);
    });
    const hostRequestId = ++permissionRequestRef.current;
    permissionSnapshotRef.current = { status: 'loading', values: {} };
    setHostPermissions({});
    setPermissionStatus('loading');
    getHostList().then((res) => {
      if (hostRequestId !== permissionRequestRef.current) return;
      if (res.code !== 200 || !Array.isArray(res.data)) throw new Error('Host list failed');
      setAgents(res.data);
      void loadHostPermissions(res.data);
    }).catch(() => {
      if (hostRequestId !== permissionRequestRef.current) return;
      permissionSnapshotRef.current = { status: 'error', values: {} };
      setPermissionStatus('error');
    });
    getClusterList().then((res) => {
      if (res.code === 200) setClusters(res.data || []);
    });
    getTagList().then((res) => {
      if (res.code === 200) setTags(res.data || []);
    });
  }, [loadHostPermissions]);

  useEffect(() => {
    fetchTasks();
    fetchOptions();
  }, [fetchTasks, fetchOptions]);

  // Detect parameters from manual script content (client-side)
  useEffect(() => {
    if (scriptMode !== 'manual') return;
    const regex = /\{\{\s*(\w+)\s*\}\}/g;
    const found = new Set<string>();
    let match;
    while ((match = regex.exec(manualScriptContent)) !== null) {
      found.add(match[1]);
    }
    const params = Array.from(found);
    setScriptParameters(params);
    setParameterValues((prev) => {
      const next: Record<string, string> = {};
      for (const p of params) {
        next[p] = prev[p] || '';
      }
      return next;
    });
  }, [manualScriptContent, scriptMode]);

  // Detect parameters from selected script (API call)
  const selectedScriptId = Form.useWatch('scriptId', form);
  useEffect(() => {
    if (scriptMode !== 'select' || !selectedScriptId) {
      if (scriptMode === 'select') {
        setScriptParameters([]);
        setParameterValues({});
      }
      return;
    }
    getScriptParameters(selectedScriptId).then((res) => {
      if (res.code === 200) {
        const params = res.data || [];
        setScriptParameters(params);
        setParameterValues((prev) => {
          const next: Record<string, string> = {};
          for (const p of params) {
            next[p] = prev[p] || '';
          }
          return next;
        });
      }
    });
  }, [selectedScriptId, scriptMode]);

  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
    }
  }, [logs]);

  useEffect(() => {
    return () => {
      ++detailRequestRef.current;
      ++workflowRequestRef.current;
      ++taskListRequestRef.current;
      ++permissionRequestRef.current;
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

    // Browsers cannot set headers on the WebSocket API, so the access token travels in the
    // query string; the server validates it during the handshake (see utils/ws.ts).
    const wsUrl = buildTaskLogWsUrl(taskId, getToken(), window.location);
    const ws = new WebSocket(wsUrl);

    ws.onopen = () => setWsStatus('connected');
    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        const line = `[${data.jobId?.slice(0, 8) || 'unknown'}] ${data.log}`;
        setHasRealLogs(true);
        setLogs((prev) => [...prev, line].slice(-MAX_TASK_LOG_LINES));
      } catch {
        setHasRealLogs(true);
        setLogs((prev) => [...prev, event.data].slice(-MAX_TASK_LOG_LINES));
      }
    };
    ws.onclose = () => setWsStatus('disconnected');
    ws.onerror = () => setWsStatus('disconnected');

    wsRef.current = ws;
  };

  const handleViewDetail = useCallback((taskId: string) => {
    const requestId = ++detailRequestRef.current;
    setDrawerOpen(true);
    setDetailLoading(true);
    setTaskDetail(null);
    if (wsRef.current) { wsRef.current.close(); wsRef.current = null; }
    if (detailTimerRef.current) { clearInterval(detailTimerRef.current); detailTimerRef.current = null; }
    setLogs([]);
    setHasRealLogs(false);
    setWsStatus('idle');
    getTaskDetail(taskId)
      .then((res) => {
        if (requestId !== detailRequestRef.current) return;
        if (res.code === 200 && res.data?.task) {
          setTaskDetail(res.data);
          connectWebSocket(taskId);
          detailTimerRef.current = setInterval(() => refreshTaskDetail(taskId), 2000);
        } else {
          message.error(res.code === 403 ? t('workflow.error.403') : t('task.detailLoadFailed'));
        }
      })
      .catch(() => { if (requestId === detailRequestRef.current) message.error(t('task.detailLoadFailed')); })
      .finally(() => { if (requestId === detailRequestRef.current) setDetailLoading(false); });
  // connectWebSocket uses stable setters and refs only.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshTaskDetail, t]);

  useEffect(() => {
    if (taskIdFromUrl) handleViewDetail(taskIdFromUrl);
  }, [taskIdFromUrl, handleViewDetail]);

  const handleCloseDrawer = () => {
    ++detailRequestRef.current;
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

  const loadWorkflows = async (page: number) => {
    const requestId = ++workflowRequestRef.current;
    setWorkflowLoading(true);
    setWorkflowPage((prev) => ({ ...prev, content: [] }));
    try {
      const res = await getWorkflowPage({ page, size: 20, status: 'published' });
      if (res.code !== 200 || !res.data) {
        if (requestId === workflowRequestRef.current) message.error(res.code === 403 ? t('workflow.error.403') : t('workflow.list.fetchFailed'));
        return;
      }
      // The list includes public workflows belonging to other users. Only a successful
      // owner/admin detail check makes a row selectable; public is not authority.
      const checked = await Promise.all(res.data.content.map(async (workflow) => {
        try {
          const detail = await getWorkflowDetail(workflow.id);
          return detail.code === 200 && detail.data?.workflow?.status === 'published' && detail.data.workflow.currentVersionId ? workflow : null;
        } catch { return null; }
      }));
      if (requestId === workflowRequestRef.current) setWorkflowPage({ ...res.data, content: checked.filter((item): item is WorkflowSummary => item !== null) });
    } catch {
      if (requestId === workflowRequestRef.current) message.error(t('workflow.list.fetchFailed'));
    } finally {
      if (requestId === workflowRequestRef.current) setWorkflowLoading(false);
    }
  };

  const selectWorkflow = async (workflow: WorkflowSummary) => {
    const requestId = ++workflowRequestRef.current;
    setWorkflowLoading(true);
    try {
      const detail = await getWorkflowDetail(workflow.id);
      if (detail.code !== 200 || !detail.data?.workflow) {
        message.error(detail.code === 403 ? t('workflow.error.403') : t('workflow.list.fetchFailed'));
        return;
      }
      const versionId = detail.data.workflow.currentVersionId;
      if (detail.data.workflow.status !== 'published' || !versionId) {
        message.warning(t('workflow.trigger.notPublished'));
        return;
      }
      const version = await getWorkflowVersion(workflow.id, versionId);
      if (version.code !== 200 || version.data?.id !== versionId || !version.data.definition) {
        message.error(version.code === 403 ? t('workflow.error.403') : t('workflow.list.fetchFailed'));
        return;
      }
      if (requestId !== workflowRequestRef.current) return;
      setWorkflowPickerOpen(false);
      setTriggerTarget({ id: workflow.id, name: detail.data.workflow.name, versionId, version: version.data.version, definition: version.data.definition });
    } catch {
      if (requestId === workflowRequestRef.current) message.error(t('workflow.list.fetchFailed'));
    } finally {
      if (requestId === workflowRequestRef.current) setWorkflowLoading(false);
    }
  };

  const handleTriggerWorkflow = async (inputs: Record<string, unknown>, idempotencyKey: string) => {
    if (!triggerTarget || triggerLoading) return;
    setTriggerLoading(true);
    try {
      const res = await triggerWorkflowRun(triggerTarget.id, { input: inputs, idempotencyKey, versionId: triggerTarget.versionId });
      if (res.code === 200 && res.data?.id) {
        setTriggerTarget(null);
        message.success(t('workflow.trigger.success'));
        navigate(`/workflow/run/${res.data.id}`);
      } else if (res.code === 403) message.error(t('workflow.error.403'));
      else if (res.code === 409) message.warning(t('workflow.trigger.notPublished'));
      else if (res.code === 429) message.warning(t('workflow.trigger.concurrency'));
      else message.error(res.message || t('workflow.trigger.failed'));
    } catch { message.error(t('workflow.trigger.failed')); }
    finally { setTriggerLoading(false); }
  };

  const handleSubmit = () => {
    // Validate manual script content before form validation
    if (scriptMode === 'manual' && !manualScriptContent.trim()) {
      message.error(t('task.pleaseInputScript'));
      return;
    }
    form.validateFields().then((values) => {
      if (targetModeRef.current !== targetMode) return;
      if (targetMode === 'agent' && (permissionSnapshotRef.current.status !== 'ready' ||
        !values.agentIds?.length || values.agentIds.some((id) => permissionSnapshotRef.current.values[id] !== true))) return;
      setSubmitting(true);
      const taskRequest: TaskCreateRequest = {
        name: values.name,
        timeoutSeconds: values.timeoutSeconds || 600,
      };

      if (targetMode === 'agent') {
        taskRequest.agentIds = values.agentIds;
      } else if (targetMode === 'cluster') {
        taskRequest.clusterIds = values.clusterIds;
      } else if (targetMode === 'tag') {
        taskRequest.tagIds = values.tagIds;
      }

      if (scriptMode === 'select' && values.scriptId) {
        taskRequest.scriptId = values.scriptId;
      } else if (scriptMode === 'manual' && manualScriptContent) {
        taskRequest.scriptContent = manualScriptContent;
      }

      if (scriptParameters.length > 0) {
        taskRequest.parameters = parameterValues;
      }

      createTask(taskRequest)
        .then((res) => {
          if (res.code === 200) {
            message.success(t('task.taskCreated'));
            form.resetFields();
            setManualScriptContent('');
            setScriptParameters([]);
            setParameterValues({});
            fetchTasks();
            if (res.data && 'id' in res.data) {
              const taskData = res.data as Task;
              setDrawerOpen(true);
              setDetailLoading(true);
              setTaskDetail(null);
              connectWebSocket(taskData.id);
              setTimeout(() => {
                getTaskDetail(taskData.id)
                  .then((detailRes) => {
                    if (detailRes.code === 200) setTaskDetail(detailRes.data);
                  })
                  .finally(() => setDetailLoading(false));
              }, 1500);
            }
          } else if (res.code === 449 && res.data) {
            // Risk detected — show confirmation dialog for approval
            const risk = res.data as RiskAssessment;
            const riskLabel = risk.overallRisk === 'BANNED' ? t('task.risk.banned') : t('task.risk.high');
            Modal.confirm({
              title: t('task.risk.title'),
              content: (
                <div>
                  <p><strong>{t('task.risk.level')}:</strong> <Tag color="red">{riskLabel}</Tag></p>
                  {risk.bannedMatches && risk.bannedMatches.length > 0 && (
                    <p><strong>{t('task.risk.bannedCommands')}:</strong> {risk.bannedMatches.join(', ')}</p>
                  )}
                  <p><strong>{t('task.risk.explanation')}:</strong> {risk.explanation}</p>
                  <p style={{ marginTop: 12, color: token.colorWarning }}>{t('task.risk.confirmHint')}</p>
                </div>
              ),
              okText: t('task.risk.submitApproval'),
              cancelText: t('task.risk.cancel'),
              onOk: () => {
                return submitTaskForApproval(taskRequest).then((approvalRes) => {
                  if (approvalRes.code === 200) {
                    message.success(t('task.risk.approvalSubmitted'));
                    form.resetFields();
                    setManualScriptContent('');
                    setScriptParameters([]);
                    setParameterValues({});
                    fetchTasks();
                  } else {
                    message.error(approvalRes.message || t('task.risk.approvalFailed'));
                  }
                });
              },
            });
          } else {
            message.error(res.message || t('task.createFailed'));
          }
        })
        .finally(() => setSubmitting(false));
    });
  };

  const onlineAgents = agents.filter((a) => a.status === 1);
  const selectedAgents = Form.useWatch('agentIds', form) || [];
  const hostSubmitBlocked = permissionStatus !== 'ready' || selectedAgents.some((id) => hostPermissions[id] !== true);

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
      <Card title={<span style={{ color: token.colorText }}>{t('task.createTask')}</span>} size="small" style={{ marginBottom: 16 }}>
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
                    onClick={() => changeTargetMode('agent')}>
                    {t('task.byHost')}
                  </Button>
                  <Button type={targetMode === 'cluster' ? 'primary' : 'default'} size="small"
                    icon={<ClusterOutlined />} onClick={() => changeTargetMode('cluster')}>
                    {t('task.byCluster')}
                  </Button>
                  <Button type={targetMode === 'tag' ? 'primary' : 'default'} size="small"
                    icon={<TagsOutlined />} onClick={() => changeTargetMode('tag')}>
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
                      label: `${a.hostname} (${a.ip})${hostPermissions[a.id] === false ? ` — ${t('task.hostExecuteDenied', { defaultValue: 'No execute permission' })}` : hostPermissions[a.id] !== true ? ` — ${t('task.hostExecuteUnknown', { defaultValue: 'Execute permission unknown' })}` : ''}`,
                      disabled: hostPermissions[a.id] !== true,
                    }))}
                    maxTagCount={3}
                  />
                </Form.Item>
              )}
              {targetMode === 'agent' && permissionStatus === 'loading' && <Alert type="info" showIcon message={t('task.hostPermissionsLoading', { defaultValue: 'Checking host execute permissions…' })} />}
              {targetMode === 'agent' && permissionStatus === 'error' && <Alert type="warning" showIcon message={t('task.hostPermissionsFailed', { defaultValue: 'Host permissions could not be checked. Retry before submitting.' })} action={<Button onClick={() => fetchOptions()}>{t('common.refresh')}</Button>} />}
              {targetMode === 'agent' && permissionStatus === 'ready' && onlineAgents.some((a) => hostPermissions[a.id] === undefined) && <Alert type="warning" showIcon message={t('task.hostPermissionsIncomplete', { defaultValue: 'Some host permissions are unknown. Retry before selecting those hosts.' })} action={<Button onClick={() => void loadHostPermissions(agents)}>{t('common.refresh')}</Button>} />}
              {targetMode !== 'agent' && <Alert type="info" showIcon message={t('task.dynamicTargetsPermissionHint', { defaultValue: 'Membership can change. Permissions for all resolved hosts are checked by the server when the task runs.' })} />}
              {targetMode === 'cluster' && (
                <Form.Item name="clusterIds" label={t('task.targetCluster')}
                  rules={[{ required: true, message: t('task.pleaseSelectCluster') }]}>
                  <Select
                    mode="multiple"
                    placeholder={t('task.clusterPlaceholder')}
                    options={clusters.map((c) => ({
                      value: c.id,
                      label: c.name,
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
                      label: tag.name,
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
                <div>
                  <div style={{ marginBottom: 8, color: token.colorText, fontWeight: 500 }}>{t('task.scriptContent')} <span style={{ color: token.colorError }}>*</span></div>
                  <div ref={taskEditorContainerRef} style={{
                    border: `1px solid ${token.colorBorderSecondary}`,
                    borderRadius: 8,
                    overflow: 'hidden',
                  }}>
                    <CodeMirror
                      value={manualScriptContent}
                      onChange={(val) => setManualScriptContent(val)}
                      extensions={editorExtensions}
                      theme={isDark ? 'dark' : 'light'}
                      height={`${taskEditorHeight}px`}
                      placeholder={"#!/bin/bash\necho 'Hello EasyShell'"}
                      basicSetup={{
                        lineNumbers: true,
                        foldGutter: true,
                        highlightActiveLine: true,
                        autocompletion: false,
                      }}
                    />
                  </div>
                  {/* Vertical resize handle */}
                  <div
                    onMouseDown={handleTaskEditorVResizeDown}
                    style={{
                      height: 6,
                      cursor: 'row-resize',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      flexShrink: 0,
                      marginTop: 2,
                    }}
                  >
                    <div style={{
                      width: 40,
                      height: 3,
                      borderRadius: 2,
                      backgroundColor: token.colorBorderSecondary,
                    }} />
                  </div>
                </div>
              )}
            </Col>
          </Row>
          {scriptParameters.length > 0 && (
            <Row gutter={16}>
              <Col span={24}>
                <div style={{
                  border: `1px solid ${token.colorBorderSecondary}`,
                  borderRadius: 8,
                  padding: 16,
                  marginBottom: 16,
                  background: token.colorFillAlter,
                }}>
                  <div style={{ marginBottom: 12, fontWeight: 500, color: token.colorText }}>
                    {t('task.parameters')}
                    <Tag color="blue" style={{ marginLeft: 8 }}>
                      {t('task.parametersDetected', { count: scriptParameters.length })}
                    </Tag>
                  </div>
                  <Row gutter={[16, 12]}>
                    {scriptParameters.map((param) => (
                      <Col span={12} key={param}>
                        <div style={{ marginBottom: 4, color: token.colorTextSecondary, fontSize: 13 }}>
                          <code style={{
                            background: token.colorFillSecondary,
                            padding: '1px 6px',
                            borderRadius: 4,
                            fontSize: 12,
                          }}>{`{{${param}}}`}</code>
                        </div>
                        <Input
                          placeholder={t('task.parameterPlaceholder', { name: param })}
                          value={parameterValues[param] || ''}
                          onChange={(e) => setParameterValues((prev) => ({ ...prev, [param]: e.target.value }))}
                        />
                      </Col>
                    ))}
                  </Row>
                </div>
              </Col>
            </Row>
          )}
          <Button type="primary" icon={<PlayCircleOutlined />} loading={submitting} disabled={targetMode === 'agent' && hostSubmitBlocked} onClick={handleSubmit}>
            {t('task.executeTask')}
          </Button>
          <Button style={{ marginLeft: 8 }} icon={<PlayCircleOutlined />} onClick={() => { setWorkflowPickerOpen(true); void loadWorkflows(0); }}>
            {t('task.runWorkflow')}
          </Button>
        </Form>
      </Card>

      <Card title={<span style={{ color: token.colorText }}>{t('task.taskList')}</span>} size="small">
        {loading ? <Spin /> : taskListError ? (
          <Alert type="error" showIcon message={taskListError === 'identity'
            ? t('resourceIdentity.unavailable', { defaultValue: 'Permissions or resource identity cannot currently be verified. Please contact an administrator.' })
            : t('resourceIdentity.fetchFailed', { defaultValue: 'Unable to load data. Check your connection and retry; contact an administrator if the problem persists.' })}
          action={<Button onClick={fetchTasks}>{t('common.refresh')}</Button>} />
        ) : <>
        <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('task.historyVisibilityNotice', { defaultValue: 'Historical tasks whose identity has not been verified and workflow tasks are temporarily not shown in this list. The data has not been deleted.' })} />
        <Table<Task>
          columns={taskColumns}
          dataSource={tasks}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1000 }}
          pagination={{ pageSize: 15 }}
        />
        </>}
      </Card>

      <Modal title={t('task.runWorkflow')} open={workflowPickerOpen} footer={null} onCancel={() => { ++workflowRequestRef.current; setWorkflowPickerOpen(false); setWorkflowLoading(false); }}>
        <Table<WorkflowSummary> rowKey="id" loading={workflowLoading} dataSource={workflowPage.content}
          columns={[
            { title: t('workflow.list.col.name'), dataIndex: 'name' },
            { title: t('workflow.list.col.version'), dataIndex: 'currentVersion', render: (version: number | null) => version != null ? `v${version}` : '-' },
            { title: t('common.actions'), render: (_, workflow) => <Button disabled={workflowLoading} onClick={() => void selectWorkflow(workflow)}>{t('workflow.trigger.action')}</Button> },
          ]}
          pagination={{ current: workflowPage.number + 1, pageSize: workflowPage.size, total: workflowPage.totalElements, showSizeChanger: false, onChange: (page) => void loadWorkflows(page - 1) }} />
      </Modal>
      {triggerTarget && <TriggerModal open definition={triggerTarget.definition} workflowName={triggerTarget.name} version={triggerTarget.version} loading={triggerLoading} onCancel={() => setTriggerTarget(null)} onSubmit={(inputs, key) => void handleTriggerWorkflow(inputs, key)} />}

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

            {taskDetail.task.scriptContent && (
              <>
                <Divider style={{ marginTop: 16 }}>{t('task.scriptContent')}</Divider>
                <pre style={{
                  background: token.colorFillAlter,
                  padding: 12,
                  borderRadius: 6,
                  maxHeight: 300,
                  overflow: 'auto',
                  fontSize: 12,
                  margin: 0,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                  color: token.colorText,
                  fontFamily: "'Cascadia Code', 'Fira Code', 'Consolas', monospace",
                }}>
                  {taskDetail.task.scriptContent}
                </pre>
              </>
            )}

            <Divider style={{ marginTop: 16 }}>{t('task.executionNodes')}</Divider>
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

            <Divider style={{ marginTop: 16 }}>{t('task.realTimeLogs')}</Divider>
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
          <div style={{ textAlign: 'center', padding: 16, color: token.colorTextTertiary }}>{t('common.loading')}</div>
        )}
      </Drawer>
    </>
  );
};

export default TaskPage;
