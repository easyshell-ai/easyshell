import { useRef, useState, useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Tag, Progress, Space, Button, message, theme, Steps, Alert,
  Modal, Form, Input, InputNumber, Drawer, Badge, Popconfirm, Typography, Empty,
} from 'antd';
import {
  DesktopOutlined, EyeOutlined, CodeOutlined, DownloadOutlined,
  PlusOutlined, HistoryOutlined, ReloadOutlined, DeleteOutlined, DisconnectOutlined, SettingOutlined,
} from '@ant-design/icons';
import { ProTable } from '@ant-design/pro-components';
import type { ProColumns, ActionType } from '@ant-design/pro-components';
import dayjs from 'dayjs';
import { getHostList, getAgentTags, deleteHost } from '../../api/host';
import { getSystemConfigList } from '../../api/system';
import { provisionHost, getProvisionList, getProvisionById, deleteProvision, retryProvision, reinstallAgent, batchReinstallAgents, uninstallAgent } from '../../api/provision';
import { hostStatusMap, provisionStatusMap, getProvisionStep, provisionStepItems, getResourceColor } from '../../utils/status';
import { formatBytes } from '../../utils/format';
import type { Agent, TagVO, HostCredentialVO } from '../../types';

const { Text } = Typography;

/* ── CSV Export Utility ── */
function exportCSV(agents: Agent[], agentTags: Record<string, TagVO[]>, t: (key: string) => string) {
  const headers = [t('host.hostname'), t('host.ipAddress'), t('host.status'), t('host.tags'), t('host.os'), t('host.arch'), 'CPU(%)', t('host.memory') + '(%)', t('host.disk') + '(%)', t('host.totalMemory') + '(GB)', t('host.agentVersion'), t('host.lastHeartbeat')];
  const rows = agents.map((a) => [
    a.hostname,
    a.ip,
    t((hostStatusMap[a.status] || hostStatusMap[0]).text),
    (agentTags[a.id] || []).map((t) => t.name).join('; '),
    a.os || '',
    a.arch || '',
    (a.cpuUsage ?? 0).toFixed(1),
    (a.memUsage ?? 0).toFixed(1),
    (a.diskUsage ?? 0).toFixed(1),
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

/* ── Component ── */
const Host: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { token } = theme.useToken();
  const actionRef = useRef<ActionType>(null);
  const [agentTags, setAgentTags] = useState<Record<string, TagVO[]>>({});
  const [dataSource, setDataSource] = useState<Agent[]>([]);
  const [serverUrlConfigured, setServerUrlConfigured] = useState(true);

  // Provision states
  const [addModalVisible, setAddModalVisible] = useState(false);
  const [historyDrawerVisible, setHistoryDrawerVisible] = useState(false);
  const [provisionRecords, setProvisionRecords] = useState<HostCredentialVO[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const pollingTimers = useRef<Record<number, ReturnType<typeof setInterval>>>({});
  const [form] = Form.useForm();
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);

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
          if (status === 'SUCCESS' || status === 'FAILED') {
            clearInterval(timer);
            delete pollingTimers.current[id];
            if (status === 'SUCCESS') {
              message.success(`${res.data.ip} ${t('host.deploySuccess')}`);
              actionRef.current?.reload();
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
        sshPassword: values.sshPassword,
      });
      if (res.code === 200 && res.data) {
        message.success(t('host.deployTaskSubmitted'));
        setProvisionRecords((prev) => [res.data, ...prev]);
        pollStatus(res.data.id);
        setAddModalVisible(false);
        form.resetFields();
        setHistoryDrawerVisible(true);
        loadProvisionHistory();
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
          if (s !== 'SUCCESS' && s !== 'FAILED' && !pollingTimers.current[r.id]) {
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
        pollStatus(res.data.id);
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
        pollStatus(res.data.id);
        setHistoryDrawerVisible(true);
        loadProvisionHistory();
      } else {
        message.error(res.message || t('host.reinstallFailed'));
      }
    } catch {
      message.error(t('host.reinstallFailed'));
    }
  }, [pollStatus, loadProvisionHistory, t]);

  const handleBatchReinstall = useCallback(async () => {
    if (selectedRowKeys.length === 0) return;
    try {
      const res = await batchReinstallAgents(selectedRowKeys as string[]);
      if (res.code === 200 && res.data) {
        message.success(t('host.batchReinstallSubmitted', { count: res.data.length }));
        setSelectedRowKeys([]);
        res.data.forEach((vo) => pollStatus(vo.id));
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
        pollStatus(res.data.id);
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

  const columns: ProColumns<Agent>[] = [
    {
      title: t('host.hostname'),
      dataIndex: 'hostname',
      key: 'hostname',
      width: 150,
      fixed: 'left',
      sorter: (a, b) => (a.hostname || '').localeCompare(b.hostname || ''),
      render: (_, record) => (
        <a onClick={() => navigate(`/host/${record.id}`)} style={{ fontWeight: 500 }}>
          <DesktopOutlined style={{ marginRight: 6 }} />{record.hostname}
        </a>
      ),
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
      dataIndex: 'status',
      key: 'status',
      width: 90,
      filters: [
        { text: t('host.online'), value: 1 },
        { text: t('host.offline'), value: 0 },
        { text: t('host.unstable'), value: 2 },
      ],
      onFilter: (value, record) => record.status === value,
      render: (_, record) => {
        const s = hostStatusMap[record.status] || hostStatusMap[0];
        return <Tag color={s.color}>{t(s.text)}</Tag>;
      },
    },
    {
      title: t('host.tags'),
      key: 'tags',
      width: 200,
      search: false,
      render: (_, record) => {
        const tags = agentTags[record.id];
        if (!tags || tags.length === 0) return '-';
        return (
          <Space size={[0, 4]} wrap>
            {tags.map((t) => <Tag key={t.id} color={t.color || 'blue'}>{t.name}</Tag>)}
          </Space>
        );
      },
    },
    {
      title: t('host.os'),
      dataIndex: 'os',
      key: 'os',
      width: 100,
    },
    {
      title: t('host.arch'),
      dataIndex: 'arch',
      key: 'arch',
      width: 90,
    },
    {
      title: 'CPU',
      dataIndex: 'cpuUsage',
      key: 'cpuUsage',
      width: 120,
      search: false,
      sorter: (a, b) => (a.cpuUsage ?? 0) - (b.cpuUsage ?? 0),
      render: (_, record) => {
        const val = record.cpuUsage ?? 0;
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
        const val = record.memUsage ?? 0;
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
        const val = record.diskUsage ?? 0;
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
      render: (_, record) => formatBytes(record.memTotal),
    },
    {
      title: t('host.agentVersion'),
      dataIndex: 'agentVersion',
      key: 'agentVersion',
      width: 110,
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
      render: (_, record) => (
        <Space size={4} wrap>
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => navigate(`/host/${record.id}`)}>
            {t('common.detail')}
          </Button>
          <Button type="link" size="small" icon={<CodeOutlined />} onClick={() => navigate(`/terminal/${record.id}`)} disabled={record.status !== 1}>
            {t('host.terminal')}
          </Button>
          <Popconfirm
            title={t('host.confirmReinstall')}
            description={t('host.reinstallDescription')}
            onConfirm={() => handleReinstall(record.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Button type="link" size="small" icon={<ReloadOutlined />}>
              {t('host.reinstall')}
            </Button>
          </Popconfirm>
          <Popconfirm
            title={t('host.confirmUninstall')}
            description={t('host.uninstallDescription')}
            onConfirm={() => handleUninstall(record.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
            okButtonProps={{ danger: true }}
          >
            <Button type="link" size="small" danger icon={<DisconnectOutlined />}>
              {t('host.uninstall')}
            </Button>
          </Popconfirm>
          <Popconfirm
            title={t('host.confirmDeleteHost')}
            description={t('host.deleteHostDescription')}
            onConfirm={() => handleDeleteHost(record.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
            okButtonProps={{ danger: true }}
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              {t('host.deleteHost')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const fetchHostData = useCallback(async () => {
    const res = await getHostList();
    if (res.code === 200) {
      const agents = res.data || [];
      setDataSource(agents);
      // Fetch tags for each agent
      agents.forEach((agent) => {
        getAgentTags(agent.id).then((tagRes) => {
          if (tagRes.code === 200) {
            setAgentTags((prev) => ({ ...prev, [agent.id]: tagRes.data || [] }));
          }
        });
      });
      return {
        data: agents,
        success: true,
        total: agents.length,
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
      <ProTable<Agent>
      columns={columns}
      actionRef={actionRef}
      request={fetchHostData}
      rowKey="id"
      search={false}
      scroll={{ x: 1600 }}
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
          selectedRowKeys.length > 0 && (
            <Popconfirm
              key="batchReinstall"
              title={t('host.confirmBatchReinstall', { count: selectedRowKeys.length })}
              onConfirm={handleBatchReinstall}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
            >
              <Button icon={<ReloadOutlined />}>
                {t('host.batchReinstall')} ({selectedRowKeys.length})
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
        persistenceKey: 'easyshell-host-table',
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

      <Modal
        title={t('host.addServer')}
        open={addModalVisible}
        onOk={handleAddServer}
        onCancel={() => {
          setAddModalVisible(false);
          form.resetFields();
        }}
        confirmLoading={submitting}
        okText={t('host.startDeploy')}
        cancelText={t('common.cancel')}
        destroyOnClose
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ sshPort: 22, sshUsername: 'root' }}
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
            name="sshPassword"
            label={t('host.password')}
            rules={[{ required: true, message: t('host.pleaseInputPassword') }]}
          >
            <Input.Password placeholder={t('host.passwordPlaceholder')} />
          </Form.Item>
        </Form>
      </Modal>

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

                  <Steps
                    size="small"
                    current={getProvisionStep(record.provisionStatus).current}
                    status={getProvisionStep(record.provisionStatus).status}
                    items={provisionStepItems.map(item => ({ ...item, title: t(item.title) }))}
                    style={{ marginBottom: 12 }}
                  />

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
                      {record.provisionStatus === 'FAILED' && (
                        <Button
                          type="link"
                          size="small"
                          icon={<ReloadOutlined />}
                          onClick={() => handleRetry(record.id)}
                        >
                          {t('common.retry')}
                        </Button>
                      )}
                      <Popconfirm
                        title={t('host.confirmDeleteRecord')}
                        onConfirm={() => handleDelete(record.id)}
                        okText={t('common.confirm')}
                        cancelText={t('common.cancel')}
                      >
                        <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                          {t('common.delete')}
                        </Button>
                      </Popconfirm>
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
