import { useRef, useState, useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Tag, Progress, Space, Button, message, theme, Steps, Alert,
  Modal, Form, Input, InputNumber, Drawer, Badge, Popconfirm, Typography, Empty,
  Radio, Checkbox, Tooltip, Upload,
} from 'antd';
import type { UploadFile } from 'antd';
import {
  DesktopOutlined, EyeOutlined, CodeOutlined, DownloadOutlined,
  PlusOutlined, HistoryOutlined, ReloadOutlined, DeleteOutlined, DisconnectOutlined, SettingOutlined,
  ImportOutlined, RocketOutlined, CloudUploadOutlined,
} from '@ant-design/icons';
import { ProTable } from '@ant-design/pro-components';
import type { ProColumns, ActionType } from '@ant-design/pro-components';
import dayjs from 'dayjs';
import { getAgentTags, deleteHost, deleteCredential } from '../../api/host';
import { getSystemConfigList } from '../../api/system';
import {
  provisionHost, getProvisionList, getProvisionById, deleteProvision, retryProvision,
  reinstallAgent, batchReinstallAgents, uninstallAgent,
  getUnifiedHostList, batchDeploy, importCsv, downloadTemplate, reinstallByCredentialId,
} from '../../api/provision';
import { hostStatusMap, provisionStatusMap, getProvisionStep, provisionStepItems, getResourceColor, uninstallStepItems, getUninstallStep } from '../../utils/status';
import { formatBytes } from '../../utils/format';
import type { TagVO, HostCredentialVO } from '../../types';

const { Text } = Typography;

/* ── CSV Export Utility ── */
function exportCSV(hosts: HostCredentialVO[], agentTags: Record<string, TagVO[]>, t: (key: string) => string) {
  const headers = [t('host.hostname'), t('host.ipAddress'), t('host.status'), t('host.tags'), t('host.os'), t('host.arch'), 'CPU(%)', t('host.memory') + '(%)', t('host.disk') + '(%)', t('host.totalMemory') + '(GB)', t('host.agentVersion'), t('host.lastHeartbeat')];
  const rows = hosts.map((a) => [
    a.hostname || a.hostName || '',
    a.ip,
    a.agentStatus != null ? t((hostStatusMap[a.agentStatus] || hostStatusMap[0]).text) : t(provisionStatusMap[a.provisionStatus]?.text || 'status.provision.pending'),
    a.agentId ? (agentTags[a.agentId] || []).map((tag) => tag.name).join('; ') : '',
    a.os || '',
    a.arch || '',
    a.cpuUsage != null ? a.cpuUsage.toFixed(1) : '',
    a.memUsage != null ? a.memUsage.toFixed(1) : '',
    a.diskUsage != null ? a.diskUsage.toFixed(1) : '',
    a.memTotal ? (a.memTotal / (1024 * 1024 * 1024)).toFixed(1) : '',
    a.agentVersion || '',
    a.lastHeartbeat ? dayjs(a.lastHeartbeat).format('YYYY-MM-DD HH:mm:ss') : '',
  ]);

  const BOM = '\uFEFF';
  const csv = BOM + [headers, ...rows].map((r) => r.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `easyshell-hosts-${dayjs().format('YYYYMMDD-HHmmss')}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

/** Compute a stable row key for unified list entries */
function getRowKey(record: HostCredentialVO): string {
  return record.id ? `c-${record.id}` : `a-${record.agentId}`;
}

/* ── Component ── */
const Host: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { token } = theme.useToken();
  const actionRef = useRef<ActionType>(null);
  const [agentTags, setAgentTags] = useState<Record<string, TagVO[]>>({});
  const [dataSource, setDataSource] = useState<HostCredentialVO[]>([]);
  const [serverUrlConfigured, setServerUrlConfigured] = useState(true);

  // Provision states
  const [addModalVisible, setAddModalVisible] = useState(false);
  const [historyDrawerVisible, setHistoryDrawerVisible] = useState(false);
  const [provisionRecords, setProvisionRecords] = useState<HostCredentialVO[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const pollingTimers = useRef<Record<number, ReturnType<typeof setInterval>>>({});
  const [form] = Form.useForm();
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);

  // Import states
  const [importModalVisible, setImportModalVisible] = useState(false);
  const [importFileList, setImportFileList] = useState<UploadFile[]>([]);
  const [importing, setImporting] = useState(false);

  // Watch form fields
  const authType = Form.useWatch('authType', form);
  const deployNow = Form.useWatch('deployNow', form);

  // Auto-refresh every 30s
  useEffect(() => {
    const timer = setInterval(() => {
      actionRef.current?.reload();
    }, 30000);
    return () => clearInterval(timer);
  }, []);

  // Check if server.external-url is configured
  useEffect(() => {
    getSystemConfigList('system').then((res) => {
      if (res.code === 200) {
        const serverUrl = (res.data || []).find((c) => c.configKey === 'server.external-url');
        setServerUrlConfigured(!!serverUrl?.configValue?.trim());
      }
    }).catch(() => { /* ignore */ });
  }, []);

  useEffect(() => {
    return () => {
      Object.values(pollingTimers.current).forEach(clearInterval);
    };
  }, []);

  const pollStatus = useCallback((id: number) => {
    const startTime = Date.now();
    const maxDuration = 5 * 60 * 1000;

    const timer = setInterval(async () => {
      if (Date.now() - startTime > maxDuration) {
        clearInterval(timer);
        delete pollingTimers.current[id];
        return;
      }
      try {
        const res = await getProvisionById(id);
        if (res.code === 200 && res.data) {
          setProvisionRecords((prev) =>
            prev.map((r) => (r.id === id ? res.data : r))
          );
          const status = res.data.provisionStatus;
          if (status === 'SUCCESS' || status === 'FAILED' || status === 'UNINSTALLED' || status === 'UNINSTALL_FAILED') {
            clearInterval(timer);
            delete pollingTimers.current[id];
            if (status === 'SUCCESS') {
              message.success(`${res.data.ip} ${t('host.deploySuccess')}`);
              actionRef.current?.reload();
            } else if (status === 'UNINSTALLED') {
              message.success(`${res.data.ip} ${t('host.uninstallSuccess')}`);
              actionRef.current?.reload();
            } else if (status === 'UNINSTALL_FAILED') {
              message.error(`${res.data.ip} ${t('host.uninstallFailed')}`);
            } else {
              message.error(`${res.data.ip} ${t('host.deployFailed')}`);
            }
          }
        }
      } catch {
        // polling continues on transient errors
      }
    }, 3000);

    pollingTimers.current[id] = timer;
  }, []);

  const handleAddServer = useCallback(async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const res = await provisionHost({
        ip: values.ip,
        sshPort: values.sshPort,
        sshUsername: values.sshUsername,
        sshPassword: values.authType === 'key' ? undefined : values.sshPassword,
        authType: values.authType,
        sshPrivateKey: values.authType === 'key' ? values.sshPrivateKey : undefined,
        hostName: values.hostName,
        deployNow: values.deployNow,
      });
      if (res.code === 200 && res.data) {
        message.success(values.deployNow ? t('host.deployTaskSubmitted') : t('host.addHostSuccess'));
        setProvisionRecords((prev) => [res.data, ...prev]);
        if (values.deployNow && res.data.id) {
          pollStatus(res.data.id);
        }
        setAddModalVisible(false);
        form.resetFields();
        actionRef.current?.reload();
        if (values.deployNow) {
          setHistoryDrawerVisible(true);
          loadProvisionHistory();
        }
      } else {
        message.error(res.message || t('common.submitFailed'));
      }
    } catch {
      // form validation error
    } finally {
      setSubmitting(false);
    }
  }, [form, pollStatus]);

  const loadProvisionHistory = useCallback(async () => {
    try {
      const res = await getProvisionList();
      if (res.code === 200) {
        setProvisionRecords(res.data || []);
        (res.data || []).forEach((r) => {
          const s = r.provisionStatus;
          if (s !== 'SUCCESS' && s !== 'FAILED' && r.id && !pollingTimers.current[r.id]) {
            pollStatus(r.id);
          }
        });
      }
    } catch {
      message.error(t('host.loadHistoryFailed'));
    }
  }, [pollStatus]);

  const handleRetry = useCallback(async (id: number) => {
    try {
      const res = await retryProvision(id);
      if (res.code === 200 && res.data) {
        message.success(t('host.retrySubmitted'));
        setProvisionRecords((prev) =>
          prev.map((r) => (r.id === id ? res.data : r))
        );
        if (res.data.id) pollStatus(res.data.id);
        actionRef.current?.reload();
      } else {
        message.error(res.message || t('host.retryFailed'));
      }
    } catch {
      message.error(t('host.retryFailed'));
    }
  }, [pollStatus, t]);

  const handleDelete = useCallback(async (id: number) => {
    try {
      const res = await deleteProvision(id);
      if (res.code === 200) {
        message.success(t('common.deleted'));
        setProvisionRecords((prev) => prev.filter((r) => r.id !== id));
        if (pollingTimers.current[id]) {
          clearInterval(pollingTimers.current[id]);
          delete pollingTimers.current[id];
        }
      } else {
        message.error(res.message || t('common.deleteFailed'));
      }
    } catch {
      message.error(t('common.deleteFailed'));
    }
  }, [t]);

  const handleReinstall = useCallback(async (agentId: string) => {
    try {
      const res = await reinstallAgent(agentId);
      if (res.code === 200 && res.data) {
        message.success(t('host.reinstallSubmitted'));
        setProvisionRecords((prev) => {
          const exists = prev.find((r) => r.id === res.data.id);
          if (exists) return prev.map((r) => (r.id === res.data.id ? res.data : r));
          return [res.data, ...prev];
        });
        if (res.data.id) pollStatus(res.data.id);
        setHistoryDrawerVisible(true);
        loadProvisionHistory();
      } else {
        message.error(res.message || t('host.reinstallFailed'));
      }
    } catch {
      message.error(t('host.reinstallFailed'));
    }
  }, [pollStatus, loadProvisionHistory, t]);

  const handleReinstallByCredential = useCallback(async (credentialId: number) => {
    try {
      const res = await reinstallByCredentialId(credentialId);
      if (res.code === 200 && res.data) {
        message.success(t('host.reinstallSubmitted'));
        setProvisionRecords((prev) => {
          const exists = prev.find((r) => r.id === res.data.id);
          if (exists) return prev.map((r) => (r.id === res.data.id ? res.data : r));
          return [res.data, ...prev];
        });
        if (res.data.id) pollStatus(res.data.id);
        setHistoryDrawerVisible(true);
        loadProvisionHistory();
        actionRef.current?.reload();
      } else {
        message.error(res.message || t('host.reinstallFailed'));
      }
    } catch {
      message.error(t('host.reinstallFailed'));
    }
  }, [pollStatus, loadProvisionHistory, t]);

  const handleBatchReinstall = useCallback(async () => {
    // Filter to only agent-based rows (keys starting with 'a-')
    const agentIds = selectedRowKeys
      .filter((k) => String(k).startsWith('a-'))
      .map((k) => String(k).slice(2));
    if (agentIds.length === 0) return;
    try {
      const res = await batchReinstallAgents(agentIds);
      if (res.code === 200 && res.data) {
        message.success(t('host.batchReinstallSubmitted', { count: res.data.length }));
        setSelectedRowKeys([]);
        res.data.forEach((vo) => { if (vo.id) pollStatus(vo.id); });
        setHistoryDrawerVisible(true);
        loadProvisionHistory();
      } else {
        message.error(res.message || t('host.batchReinstallFailed'));
      }
    } catch {
      message.error(t('host.batchReinstallFailed'));
    }
  }, [selectedRowKeys, pollStatus, loadProvisionHistory, t]);


  const handleUninstall = useCallback(async (agentId: string) => {
    try {
      const res = await uninstallAgent(agentId);
      if (res.code === 200 && res.data) {
        message.success(t('host.uninstallSubmitted'));
        setProvisionRecords((prev) => {
          const exists = prev.find((r) => r.id === res.data.id);
          if (exists) return prev.map((r) => (r.id === res.data.id ? res.data : r));
          return [res.data, ...prev];
        });
        if (res.data.id) pollStatus(res.data.id);
        setHistoryDrawerVisible(true);
        loadProvisionHistory();
      } else {
        message.error(res.message || t('host.uninstallFailed'));
      }
    } catch {
      message.error(t('host.uninstallFailed'));
    }
  }, [pollStatus, loadProvisionHistory, t]);

  const handleDeleteHost = useCallback(async (agentId: string) => {
    try {
      const res = await deleteHost(agentId);
      if (res.code === 200) {
        message.success(t('host.deleteHostSuccess'));
        actionRef.current?.reload();
      } else {
        message.error(res.message || t('host.deleteHostFailed'));
      }
    } catch {
      message.error(t('host.deleteHostFailed'));
    }
  }, [t]);

  const handleDeleteCredential = useCallback(async (id: number) => {
    try {
      const res = await deleteCredential(id);
      if (res.code === 200) {
        message.success(t('common.deleted'));
        actionRef.current?.reload();
      } else {
        message.error(res.message || t('common.deleteFailed'));
      }
    } catch {
      message.error(t('common.deleteFailed'));
    }
  }, [t]);

  const handleBatchDeploy = useCallback(async () => {
    // Filter selected rows to get credential IDs where status is PENDING, FAILED, or UNINSTALLED
    const credentialIds = selectedRowKeys
      .filter((k) => String(k).startsWith('c-'))
      .map((k) => Number(String(k).slice(2)))
      .filter((id) => {
        const record = dataSource.find((r) => r.id === id);
        return record && (record.provisionStatus === 'PENDING' || record.provisionStatus === 'FAILED' || record.provisionStatus === 'UNINSTALLED');
      });
    if (credentialIds.length === 0) return;
    try {
      const res = await batchDeploy(credentialIds);
      if (res.code === 200 && res.data) {
        message.success(t('host.batchDeploySubmitted', { count: res.data.length }));
        setSelectedRowKeys([]);
        res.data.forEach((vo) => { if (vo.id) pollStatus(vo.id); });
        actionRef.current?.reload();
        setHistoryDrawerVisible(true);
        loadProvisionHistory();
      } else {
        message.error(res.message || t('host.batchDeployFailed'));
      }
    } catch {
      message.error(t('host.batchDeployFailed'));
    }
  }, [selectedRowKeys, dataSource, pollStatus, loadProvisionHistory, t]);

  const handleImportCsv = useCallback(async () => {
    if (importFileList.length === 0 || !importFileList[0].originFileObj) return;
    setImporting(true);
    try {
      const res = await importCsv(importFileList[0].originFileObj);
      if (res.code === 200 && res.data) {
        message.success(t('host.importSuccess', { count: res.data.length }));
        setImportModalVisible(false);
        setImportFileList([]);
        actionRef.current?.reload();
        // Auto-deploy triggered by backend — open deploy history to show progress
        setHistoryDrawerVisible(true);
        loadProvisionHistory();
      } else {
        message.error(res.message || t('host.importFailed'));
      }
    } catch {
      message.error(t('host.importFailed'));
    } finally {
      setImporting(false);
    }
  }, [importFileList, t, loadProvisionHistory]);

  // Count pending/failed/uninstalled selected for batch deploy
  const pendingSelectedCount = selectedRowKeys.filter((k) => {
    if (!String(k).startsWith('c-')) return false;
    const id = Number(String(k).slice(2));
    const record = dataSource.find((r) => r.id === id);
    return record && (record.provisionStatus === 'PENDING' || record.provisionStatus === 'FAILED' || record.provisionStatus === 'UNINSTALLED');
  }).length;

  // Count agent-based selected for batch reinstall
  const agentSelectedCount = selectedRowKeys.filter((k) => String(k).startsWith('a-') || (() => {
    if (!String(k).startsWith('c-')) return false;
    const id = Number(String(k).slice(2));
    const record = dataSource.find((r) => r.id === id);
    return record && record.agentId;
  })()).length;

  const isDeployingStatus = (status: string) =>
    ['CONNECTING', 'UPLOADING', 'INSTALLING', 'STARTING', 'UNINSTALLING'].includes(status);

  const columns: ProColumns<HostCredentialVO>[] = [
    {
      title: t('host.hostname'),
      dataIndex: 'hostname',
      key: 'hostname',
      width: 150,
      fixed: 'left',
      sorter: (a, b) => (a.hostname || a.hostName || '').localeCompare(b.hostname || b.hostName || ''),
      render: (_, record) => {
        const displayName = record.hostName || record.hostname || record.ip;
        if (record.agentId) {
          return (
            <a onClick={() => navigate(`/host/${record.agentId}`)} style={{ fontWeight: 500 }}>
              <DesktopOutlined style={{ marginRight: 6 }} />{displayName}
            </a>
          );
        }
        return (
          <span style={{ fontWeight: 500 }}>
            <DesktopOutlined style={{ marginRight: 6 }} />{displayName}
          </span>
        );
      },
    },
    {
      title: t('host.ipAddress'),
      dataIndex: 'ip',
      key: 'ip',
      width: 140,
      sorter: (a, b) => (a.ip || '').localeCompare(b.ip || ''),
    },
    {
      title: t('host.status'),
      key: 'status',
      width: 110,
      render: (_, record) => {
        if (record.agentStatus != null) {
          const s = hostStatusMap[record.agentStatus] || hostStatusMap[0];
          return <Tag color={s.color}>{t(s.text)}</Tag>;
        }
        const ps = provisionStatusMap[record.provisionStatus] || provisionStatusMap.PENDING;
        return (
          <Tag color={ps.color} icon={ps.icon}>
            {t(ps.text)}
          </Tag>
        );
      },
    },
    {
      title: t('host.provisionStatus'),
      dataIndex: 'provisionStatus',
      key: 'provisionStatus',
      width: 110,
      filters: [
        { text: t('host.statusPending'), value: 'PENDING' },
        { text: t('host.statusDeployed'), value: 'SUCCESS' },
        { text: t('host.statusFailed'), value: 'FAILED' },
      ],
      onFilter: (value, record) => record.provisionStatus === value,
      render: (_, record) => {
        if (!record.provisionStatus) return '-';
        const ps = provisionStatusMap[record.provisionStatus] || provisionStatusMap.PENDING;
        return (
          <Tag color={ps.color} icon={ps.icon}>
            {t(ps.text)}
          </Tag>
        );
      },
    },
    {
      title: t('host.tags'),
      key: 'tags',
      width: 200,
      search: false,
      render: (_, record) => {
        if (!record.agentId) return '-';
        const tags = agentTags[record.agentId];
        if (!tags || tags.length === 0) return '-';
        return (
          <Space size={[0, 4]} wrap>
            {tags.map((tag) => <Tag key={tag.id} color={tag.color || 'blue'}>{tag.name}</Tag>)}
          </Space>
        );
      },
    },
    {
      title: t('host.os'),
      dataIndex: 'os',
      key: 'os',
      width: 100,
      render: (_, record) => record.os || '-',
    },
    {
      title: t('host.arch'),
      dataIndex: 'arch',
      key: 'arch',
      width: 90,
      render: (_, record) => record.arch || '-',
    },
    {
      title: 'CPU',
      dataIndex: 'cpuUsage',
      key: 'cpuUsage',
      width: 120,
      search: false,
      sorter: (a, b) => (a.cpuUsage ?? 0) - (b.cpuUsage ?? 0),
      render: (_, record) => {
        if (record.cpuUsage == null) return '-';
        const val = record.cpuUsage;
        return <Progress percent={Number(val.toFixed(1))} size="small"
          strokeColor={getResourceColor(val)} />;
      },
    },
    {
      title: t('host.memory'),
      dataIndex: 'memUsage',
      key: 'memUsage',
      width: 120,
      search: false,
      sorter: (a, b) => (a.memUsage ?? 0) - (b.memUsage ?? 0),
      render: (_, record) => {
        if (record.memUsage == null) return '-';
        const val = record.memUsage;
        return <Progress percent={Number(val.toFixed(1))} size="small"
          strokeColor={getResourceColor(val)} />;
      },
    },
    {
      title: t('host.disk'),
      dataIndex: 'diskUsage',
      key: 'diskUsage',
      width: 120,
      search: false,
      sorter: (a, b) => (a.diskUsage ?? 0) - (b.diskUsage ?? 0),
      render: (_, record) => {
        if (record.diskUsage == null) return '-';
        const val = record.diskUsage;
        return <Progress percent={Number(val.toFixed(1))} size="small"
          strokeColor={getResourceColor(val)} />;
      },
    },
    {
      title: t('host.totalMemory'),
      dataIndex: 'memTotal',
      key: 'memTotal',
      width: 100,
      search: false,
      sorter: (a, b) => (a.memTotal ?? 0) - (b.memTotal ?? 0),
      render: (_, record) => record.memTotal ? formatBytes(record.memTotal) : '-',
    },
    {
      title: t('host.agentVersion'),
      dataIndex: 'agentVersion',
      key: 'agentVersion',
      width: 110,
      render: (_, record) => record.agentVersion || '-',
    },
    {
      title: t('host.lastHeartbeat'),
      dataIndex: 'lastHeartbeat',
      key: 'lastHeartbeat',
      width: 170,
      search: false,
      sorter: (a, b) => new Date(a.lastHeartbeat || 0).getTime() - new Date(b.lastHeartbeat || 0).getTime(),
      render: (_, record) => record.lastHeartbeat ? dayjs(record.lastHeartbeat).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
    {
      title: t('common.actions'),
      key: 'action',
      width: 320,
      fixed: 'right',
      search: false,
      render: (_, record) => {
        // 1. Deployed host (has agent) — always show full actions
        if (record.agentId) {
          return (
            <Space size={4} wrap>
              <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => navigate(`/host/${record.agentId}`)}>{t('common.detail')}</Button>
              <Button type="link" size="small" icon={<CodeOutlined />} onClick={() => navigate(`/terminal/${record.agentId}`)} disabled={record.agentStatus !== 1}>{t('host.terminal')}</Button>
              <Popconfirm title={t('host.confirmReinstall')} description={t('host.reinstallDescription')} onConfirm={() => handleReinstall(record.agentId!)} okText={t('common.confirm')} cancelText={t('common.cancel')}>
                <Button type="link" size="small" icon={<ReloadOutlined />}>{t('host.reinstall')}</Button>
              </Popconfirm>
              <Popconfirm title={t('host.confirmUninstall')} description={t('host.uninstallDescription')} onConfirm={() => handleUninstall(record.agentId!)} okText={t('common.confirm')} cancelText={t('common.cancel')} okButtonProps={{ danger: true }}>
                <Button type="link" size="small" danger icon={<DisconnectOutlined />}>{t('host.uninstall')}</Button>
              </Popconfirm>
              <Popconfirm title={t('host.confirmDeleteHost')} description={t('host.deleteHostDescription')} onConfirm={() => handleDeleteHost(record.agentId!)} okText={t('common.confirm')} cancelText={t('common.cancel')} okButtonProps={{ danger: true }}>
                <Button type="link" size="small" danger icon={<DeleteOutlined />}>{t('host.deleteHost')}</Button>
              </Popconfirm>
            </Space>
          );
        }

        // 2. Deploying in progress — show status text only
        if (isDeployingStatus(record.provisionStatus)) {
          return <Text type="secondary">{t(provisionStatusMap[record.provisionStatus]?.text || 'status.provision.pending')}</Text>;
        }

        // 2.5. Uninstalled host — show reinstall by credential & delete
        if (record.provisionStatus === 'UNINSTALLED' && record.id) {
          return (
            <Space size={4} wrap>
              <Button type="link" size="small" icon={<RocketOutlined />}
                onClick={() => handleReinstallByCredential(record.id!)}>{t('host.reinstall')}</Button>
              <Popconfirm title={t('host.confirmDeleteCredential')}
                onConfirm={() => handleDeleteCredential(record.id!)} okText={t('common.confirm')} cancelText={t('common.cancel')}>
                <Button type="link" size="small" danger icon={<DeleteOutlined />}>{t('host.deleteCredential')}</Button>
              </Popconfirm>
            </Space>
          );
        }

        // 3. Pending or failed host (no agent yet) — show deploy & delete
        if (record.provisionStatus === 'PENDING' || record.provisionStatus === 'FAILED') {
          return (
            <Space size={4} wrap>
              {record.id && (
                <Button type="link" size="small" icon={<RocketOutlined />} onClick={() => handleRetry(record.id!)}>{t('host.deploy')}</Button>
              )}
              {record.id && (
                <Popconfirm title={t('host.confirmDeleteCredential')} onConfirm={() => handleDeleteCredential(record.id!)} okText={t('common.confirm')} cancelText={t('common.cancel')}>
                  <Button type="link" size="small" danger icon={<DeleteOutlined />}>{t('host.deleteCredential')}</Button>
                </Popconfirm>
              )}
            </Space>
          );
        }

        // 4. SUCCESS but agent not registered yet — show retry deploy
        if (record.provisionStatus === 'SUCCESS' && record.id) {
          return (
            <Space size={4} wrap>
              <Button type="link" size="small" icon={<RocketOutlined />} onClick={() => handleRetry(record.id!)}>{t('host.reinstall')}</Button>
              <Popconfirm title={t('host.confirmDeleteCredential')} onConfirm={() => handleDeleteCredential(record.id!)} okText={t('common.confirm')} cancelText={t('common.cancel')}>
                <Button type="link" size="small" danger icon={<DeleteOutlined />}>{t('host.deleteCredential')}</Button>
              </Popconfirm>
            </Space>
          );
        }

        return '-';
      },
    },
  ];

  const fetchHostData = useCallback(async () => {
    const res = await getUnifiedHostList();
    if (res.code === 200) {
      const entries = res.data || [];
      setDataSource(entries);
      // Fetch tags for entries that have agents
      entries.forEach((entry) => {
        if (entry.agentId) {
          getAgentTags(entry.agentId).then((tagRes) => {
            if (tagRes.code === 200) {
              setAgentTags((prev) => ({ ...prev, [entry.agentId!]: tagRes.data || [] }));
            }
          });
        }
      });
      return {
        data: entries,
        success: true,
        total: entries.length,
      };
    }
    return { data: [], success: false, total: 0 };
  }, []);

  return (
    <>
      {!serverUrlConfigured && (
        <Alert
          message={t('host.serverUrlNotConfiguredTitle')}
          description={t('host.serverUrlNotConfigured')}
          type="warning"
          showIcon
          banner
          action={
            <Button size="small" type="primary" icon={<SettingOutlined />} onClick={() => navigate('/system/config')}>
              {t('nav.system_config')}
            </Button>
          }
          style={{ marginBottom: 16 }}
        />
      )}
      <ProTable<HostCredentialVO>
      columns={columns}
      actionRef={actionRef}
      request={fetchHostData}
      rowKey={getRowKey}
      search={false}
      scroll={{ x: 1800 }}
      rowSelection={{
        selectedRowKeys,
        onChange: (keys) => setSelectedRowKeys(keys),
      }}
      headerTitle={
        <Space>
          <DesktopOutlined style={{ color: token.colorPrimary }} />
          <span>{t('host.management')}</span>
          <Tag>{dataSource.length} {t('host.units')}</Tag>
        </Space>
      }
      options={{
        density: true,
        setting: {
          listsHeight: 400,
        },
        reload: true,
      }}
      toolBarRender={() => [
          pendingSelectedCount > 0 && (
            <Popconfirm
              key="batchDeploy"
              title={t('host.confirmBatchDeploy', { count: pendingSelectedCount })}
              onConfirm={handleBatchDeploy}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
            >
              <Button icon={<RocketOutlined />} type="primary">
                {t('host.batchDeploy')} ({pendingSelectedCount})
              </Button>
            </Popconfirm>
          ),
          agentSelectedCount > 0 && (
            <Popconfirm
              key="batchReinstall"
              title={t('host.confirmBatchReinstall', { count: agentSelectedCount })}
              onConfirm={handleBatchReinstall}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
            >
              <Button icon={<ReloadOutlined />}>
                {t('host.batchReinstall')} ({agentSelectedCount})
              </Button>
            </Popconfirm>
          ),
          <Button
            key="add"
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setAddModalVisible(true)}
          >
            {t('host.addServer')}
          </Button>,
          <Button
            key="import"
            icon={<ImportOutlined />}
            onClick={() => setImportModalVisible(true)}
          >
            {t('host.importCSV')}
          </Button>,
          <Button
            key="history"
            icon={<HistoryOutlined />}
            onClick={() => {
              setHistoryDrawerVisible(true);
              loadProvisionHistory();
            }}
          >
            {t('host.deployHistory')}
          </Button>,
          <Button
            key="export"
            icon={<DownloadOutlined />}
            onClick={() => {
              exportCSV(dataSource, agentTags, t);
              message.success(t('common.exportSuccess'));
            }}
            disabled={dataSource.length === 0}
          >
            {t('host.exportCSV')}
          </Button>,
        ]}
      columnsState={{
        persistenceKey: 'easyshell-host-table-v2',
        persistenceType: 'localStorage',
      }}
      pagination={{
        pageSize: 20,
        showSizeChanger: true,
        pageSizeOptions: ['10', '20', '50', '100'],
        showTotal: (total, range) => `${range[0]}-${range[1]} / ${total} ${t('host.units')}`,
      }}
      cardBordered
      />

      {/* Add Server Modal */}
      <Modal
        title={t('host.addServer')}
        open={addModalVisible}
        onOk={handleAddServer}
        onCancel={() => {
          setAddModalVisible(false);
          form.resetFields();
        }}
        confirmLoading={submitting}
        okText={deployNow ? t('host.addAndDeploy') : t('host.addOnly')}
        cancelText={t('common.cancel')}
        destroyOnClose
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ sshPort: 22, sshUsername: 'root', authType: 'password', deployNow: false }}
        >
          <Form.Item
            name="ip"
            label={t('host.ipAddress')}
            rules={[
              { required: true, message: t('host.pleaseInputIP') },
              { pattern: /^(\d{1,3}\.){3}\d{1,3}$/, message: t('host.invalidIP') },
            ]}
          >
            <Input placeholder={t('host.ipPlaceholder')} />
          </Form.Item>
          <Form.Item
            name="hostName"
            label={t('host.hostName')}
          >
            <Input placeholder={t('host.hostNamePlaceholder')} />
          </Form.Item>
          <Form.Item
            name="sshPort"
            label={t('host.sshPort')}
            rules={[{ required: true, message: t('host.pleaseInputPort') }]}
          >
            <InputNumber min={1} max={65535} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="sshUsername"
            label={t('host.username')}
            rules={[{ required: true, message: t('host.pleaseInputUsername') }]}
          >
            <Input placeholder="root" />
          </Form.Item>
          <Form.Item
            name="authType"
            label={t('host.authType')}
          >
            <Radio.Group>
              <Radio value="password">{t('host.authTypePassword')}</Radio>
              <Radio value="key">{t('host.authTypeKey')}</Radio>
            </Radio.Group>
          </Form.Item>
          {authType !== 'key' && (
            <Form.Item
              name="sshPassword"
              label={t('host.password')}
              rules={[{ required: authType !== 'key', message: t('host.pleaseInputPassword') }]}
            >
              <Input.Password placeholder={t('host.passwordPlaceholder')} />
            </Form.Item>
          )}
          {authType === 'key' && (
            <Form.Item
              name="sshPrivateKey"
              label={t('host.sshPrivateKey')}
              rules={[{ required: authType === 'key', message: t('host.pleaseInputPrivateKey') }]}
            >
              <Input.TextArea rows={6} placeholder={t('host.privateKeyPlaceholder')} />
            </Form.Item>
          )}
          <Form.Item name="deployNow" valuePropName="checked">
            <Tooltip title={t('host.deployNowTip')}>
              <Checkbox>{t('host.deployNow')}</Checkbox>
            </Tooltip>
          </Form.Item>
        </Form>
      </Modal>

      {/* CSV Import Modal */}
      <Modal
        title={t('host.importTitle')}
        open={importModalVisible}
        onOk={handleImportCsv}
        onCancel={() => {
          setImportModalVisible(false);
          setImportFileList([]);
        }}
        confirmLoading={importing}
        okText={t('host.importCSV')}
        cancelText={t('common.cancel')}
        okButtonProps={{ disabled: importFileList.length === 0 }}
      >
        <p style={{ marginBottom: 16 }}>{t('host.importDescription')}</p>
        <Space direction="vertical" style={{ width: '100%' }} size={16}>
          <Button icon={<DownloadOutlined />} onClick={() => downloadTemplate()}>
            {t('host.downloadTemplate')}
          </Button>
          <Upload
            accept=".csv"
            maxCount={1}
            fileList={importFileList}
            beforeUpload={() => false}
            onChange={({ fileList }) => setImportFileList(fileList)}
          >
            <Button icon={<CloudUploadOutlined />}>{t('host.selectCsvFile')}</Button>
          </Upload>
        </Space>
      </Modal>

      {/* Deploy History Drawer */}
      <Drawer
        title={t('host.deployHistory')}
        open={historyDrawerVisible}
        onClose={() => setHistoryDrawerVisible(false)}
        width={640}
        extra={
          <Button
            icon={<ReloadOutlined />}
            onClick={loadProvisionHistory}
            size="small"
          >
            {t('common.refresh')}
          </Button>
        }
      >
        {provisionRecords.length === 0 ? (
          <Empty description={t('host.noDeployRecords')} />
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {provisionRecords.map((record) => {
              const statusInfo = provisionStatusMap[record.provisionStatus] || provisionStatusMap.PENDING;
              return (
                <div
                  key={record.id}
                  style={{
                    border: `1px solid ${token.colorBorderSecondary}`,
                    borderRadius: token.borderRadiusLG,
                    padding: 16,
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                    <Space>
                      <Text strong>{record.ip}</Text>
                      <Text type="secondary">:{record.sshPort}</Text>
                      <Text type="secondary">({record.sshUsername})</Text>
                    </Space>
                    <Badge
                      status={statusInfo.color as 'default' | 'processing' | 'success' | 'error'}
                      text={
                        <Space size={4}>
                          {statusInfo.icon}
                          <span>{t(statusInfo.text)}</span>
                        </Space>
                      }
                    />
                  </div>

                  {(() => {
                    const isUninstall = ['UNINSTALLING', 'UNINSTALLED', 'UNINSTALL_FAILED'].includes(record.provisionStatus);
                    const stepItems = isUninstall ? uninstallStepItems : provisionStepItems;
                    const stepInfo = isUninstall ? getUninstallStep(record.provisionStatus) : getProvisionStep(record.provisionStatus);
                    return (
                      <Steps
                        size="small"
                        current={stepInfo.current}
                        status={stepInfo.status}
                        items={stepItems.map(item => ({ ...item, title: t(item.title) }))}
                        style={{ marginBottom: 12 }}
                      />
                    );
                  })()}

                  {record.provisionLog && (
                    <pre
                      style={{
                        background: token.colorBgLayout,
                        padding: 12,
                        borderRadius: token.borderRadius,
                        fontSize: 12,
                        lineHeight: 1.6,
                        maxHeight: 200,
                        overflow: 'auto',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-all',
                        margin: '8px 0',
                      }}
                    >
                      {record.provisionLog}
                    </pre>
                  )}

                  {record.errorMessage && (
                    <Text type="danger" style={{ fontSize: 12, display: 'block', margin: '8px 0' }}>
                      {record.errorMessage}
                    </Text>
                  )}

                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 8 }}>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {record.createdAt}
                    </Text>
                    <Space size={8}>
                      {record.provisionStatus === 'FAILED' && record.id && (
                        <Button
                          type="link"
                          size="small"
                          icon={<ReloadOutlined />}
                          onClick={() => handleRetry(record.id!)}
                        >
                          {t('common.retry')}
                        </Button>
                      )}
                      {record.id && (
                        <Popconfirm
                          title={t('host.confirmDeleteRecord')}
                          onConfirm={() => handleDelete(record.id!)}
                          okText={t('common.confirm')}
                          cancelText={t('common.cancel')}
                        >
                          <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                            {t('common.delete')}
                          </Button>
                        </Popconfirm>
                      )}
                    </Space>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </Drawer>
    </>
  );
};

export default Host;
