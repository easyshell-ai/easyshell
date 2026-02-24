import { useState, useEffect, useCallback } from 'react';
import {
  Card,
  Table,
  Button,
  Space,
  Tag,
  Modal,
  Form,
  Input,
  Select,
  Switch,
  InputNumber,
  message,
  Popconfirm,
  Typography,
  Tooltip,
  theme,
  AutoComplete,
  Checkbox,
  Divider,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  RobotOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  listAgents,
  createAgent,
  updateAgent,
  toggleAgent,
  deleteAgent,
  getAvailableTools,
  getAvailableProviders,
} from '../../api/agent';
import type { AgentDefinitionVO, AgentDefinitionRequest, AvailableTool, AvailableProvider } from '../../api/agent';

const { Title, Paragraph } = Typography;
const { TextArea } = Input;

const BUILT_IN_AGENTS = new Set([
  'orchestrator', 'explore', 'execute', 'analyze', 'planner', 'reviewer',
]);

const modeColorMap: Record<string, string> = {
  primary: 'blue',
  subagent: 'green',
};

const BUILTIN_PROVIDERS: Record<string, { label: string; models: string[] }> = {
  openai: { label: 'OpenAI', models: ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo', 'gpt-3.5-turbo', 'o1', 'o1-mini', 'o3-mini'] },
  anthropic: { label: 'Anthropic', models: ['claude-sonnet-4-20250514', 'claude-opus-4-20250514', 'claude-3-5-haiku-20241022'] },
  gemini: { label: 'Gemini', models: ['gemini-2.0-flash', 'gemini-2.0-flash-lite', 'gemini-1.5-pro'] },
  ollama: { label: 'Ollama', models: [] },
};

const AgentConfig: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const [agents, setAgents] = useState<AgentDefinitionVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingAgent, setEditingAgent] = useState<AgentDefinitionVO | null>(null);
  const [form] = Form.useForm();
  const [availableTools, setAvailableTools] = useState<AvailableTool[]>([]);
  const [availableProviders, setAvailableProviders] = useState<AvailableProvider[]>([]);
  const [selectedProvider, setSelectedProvider] = useState<string>('');
  const [permAllowAll, setPermAllowAll] = useState(true);
  const [permSelectedTools, setPermSelectedTools] = useState<string[]>([]);

  const fetchAgents = useCallback(() => {
    setLoading(true);
    listAgents()
      .then((res) => {
        if (res.code === 200) {
          setAgents(res.data || []);
        }
      })
      .catch(() => message.error(t('agent.fetchError')))
      .finally(() => setLoading(false));
  }, [t]);

  useEffect(() => {
    fetchAgents();
  }, [fetchAgents]);

  useEffect(() => {
    getAvailableTools().then(res => { if (res.code === 200) setAvailableTools(res.data || []); });
    getAvailableProviders().then(res => { if (res.code === 200) setAvailableProviders(res.data || []); });
  }, []);

  const getModelOptions = (provider: string) => {
    const builtin = BUILTIN_PROVIDERS[provider];
    if (!builtin) return [];
    return builtin.models.map(m => ({ value: m, label: m }));
  };

  const openCreate = () => {
    setEditingAgent(null);
    form.resetFields();
    form.setFieldsValue({
      mode: 'subagent',
      maxIterations: 5,
      enabled: true,
      permissions: '[{"tool":"*","action":"allow"}]',
    });
    setSelectedProvider('');
    setPermAllowAll(true);
    setPermSelectedTools([]);
    setModalOpen(true);
  };

  const openEdit = (agent: AgentDefinitionVO) => {
    setEditingAgent(agent);
    form.setFieldsValue({
      name: agent.name,
      displayName: agent.displayName,
      mode: agent.mode,
      permissions: agent.permissions,
      modelProvider: agent.modelProvider,
      modelName: agent.modelName,
      systemPrompt: agent.systemPrompt,
      maxIterations: agent.maxIterations,
      enabled: agent.enabled,
      description: agent.description,
    });
    setSelectedProvider(agent.modelProvider || '');
    try {
      const perms = JSON.parse(agent.permissions || '[]');
      const hasWildcard = perms.some((p: { tool: string; action: string }) => p.tool === '*' && p.action === 'allow');
      setPermAllowAll(hasWildcard);
      if (!hasWildcard) {
        setPermSelectedTools(perms.filter((p: { tool: string; action: string }) => p.action === 'allow').map((p: { tool: string }) => p.tool));
      } else {
        setPermSelectedTools([]);
      }
    } catch {
      setPermAllowAll(true);
      setPermSelectedTools([]);
    }
    setModalOpen(true);
  };

  const handleSubmit = async (values: AgentDefinitionRequest) => {
    if (permAllowAll) {
      values.permissions = JSON.stringify([{ tool: '*', action: 'allow' }]);
    } else {
      values.permissions = JSON.stringify(
        permSelectedTools.map(name => ({ tool: name, action: 'allow' }))
      );
    }
    try {
      if (editingAgent) {
        const res = await updateAgent(editingAgent.id, values);
        if (res.code === 200) {
          message.success(t('agent.updateSuccess'));
          setModalOpen(false);
          fetchAgents();
        } else {
          message.error(res.message || t('agent.updateError'));
        }
      } else {
        const res = await createAgent(values);
        if (res.code === 200) {
          message.success(t('agent.createSuccess'));
          setModalOpen(false);
          fetchAgents();
        } else {
          message.error(res.message || t('agent.createError'));
        }
      }
    } catch {
      message.error(editingAgent ? t('agent.updateError') : t('agent.createError'));
    }
  };

  const handleToggle = async (id: number) => {
    try {
      const res = await toggleAgent(id);
      if (res.code === 200) {
        message.success(t('agent.toggleSuccess'));
        fetchAgents();
      } else {
        message.error(res.message || t('agent.toggleError'));
      }
    } catch {
      message.error(t('agent.toggleError'));
    }
  };

  const handleDelete = async (id: number) => {
    try {
      const res = await deleteAgent(id);
      if (res.code === 200) {
        message.success(t('agent.deleteSuccess'));
        fetchAgents();
      } else {
        message.error(res.message || t('agent.deleteError'));
      }
    } catch {
      message.error(t('agent.deleteError'));
    }
  };

  const columns: ColumnsType<AgentDefinitionVO> = [
    {
      title: t('agent.col.name'),
      dataIndex: 'name',
      key: 'name',
      width: 140,
      render: (text: string, record: AgentDefinitionVO) => (
        <Space>
          <RobotOutlined style={{ color: token.colorPrimary }} />
          <span style={{ fontWeight: 500 }}>{record.displayName || text}</span>
          {BUILT_IN_AGENTS.has(text) && (
            <Tag color="purple" style={{ fontSize: 10 }}>{t('agent.builtIn')}</Tag>
          )}
        </Space>
      ),
    },
    {
      title: t('agent.col.mode'),
      dataIndex: 'mode',
      key: 'mode',
      width: 100,
      render: (mode: string) => (
        <Tag color={modeColorMap[mode] || 'default'}>{mode}</Tag>
      ),
    },
    {
      title: t('agent.col.model'),
      key: 'model',
      width: 200,
      render: (_: unknown, record: AgentDefinitionVO) => {
        if (record.modelProvider || record.modelName) {
          return (
            <span style={{ fontSize: 12, color: token.colorTextSecondary }}>
              {record.modelProvider ? `${record.modelProvider} / ` : ''}{record.modelName || '-'}
            </span>
          );
        }
        return <Tag>{t('agent.inheritModel')}</Tag>;
      },
    },
    {
      title: t('agent.col.maxIterations'),
      dataIndex: 'maxIterations',
      key: 'maxIterations',
      width: 80,
      align: 'center',
    },
    {
      title: t('agent.col.enabled'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (enabled: boolean, record: AgentDefinitionVO) => (
        <Switch
          checked={enabled}
          size="small"
          onChange={() => handleToggle(record.id)}
        />
      ),
    },
    {
      title: t('agent.col.description'),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (text: string) => text || '-',
    },
    {
      title: t('agent.col.actions'),
      key: 'actions',
      width: 160,
      render: (_: unknown, record: AgentDefinitionVO) => (
        <Space size="small">
          <Tooltip title={t('common.edit')}>
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => openEdit(record)}
            />
          </Tooltip>
          {!BUILT_IN_AGENTS.has(record.name) && (
            <Popconfirm
              title={t('agent.confirmDelete')}
              onConfirm={() => handleDelete(record.id)}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
            >
              <Tooltip title={t('common.delete')}>
                <Button type="link" size="small" danger icon={<DeleteOutlined />} />
              </Tooltip>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} style={{ margin: 0, color: token.colorText }}>
          {t('agent.title')}
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          {t('agent.create')}
        </Button>
      </div>

      <Card style={{ borderRadius: 12 }} styles={{ body: { padding: 0 } }}>
        <Table<AgentDefinitionVO>
          columns={columns}
          dataSource={agents}
          rowKey="id"
          loading={loading}
          pagination={{
            pageSize: 20,
            showTotal: (total) => t('agent.total', { count: total }),
          }}
        />
      </Card>

      <Modal
        title={editingAgent ? t('agent.editTitle') : t('agent.createTitle')}
        open={modalOpen}
        onCancel={() => {
          setModalOpen(false);
          setEditingAgent(null);
        }}
        footer={null}
        destroyOnClose
        width={640}
      >
        <Form form={form} layout="vertical" onFinish={handleSubmit}>
          <Form.Item
            name="name"
            label={t('agent.field.name')}
            rules={[
              { required: true, message: t('agent.field.nameRequired') },
              { max: 50, message: t('agent.field.nameMax') },
            ]}
          >
            <Input placeholder={t('agent.field.namePlaceholder')} disabled={!!editingAgent} />
          </Form.Item>

          <Form.Item name="displayName" label={t('agent.field.displayName')}>
            <Input placeholder={t('agent.field.displayNamePlaceholder')} />
          </Form.Item>

          <Form.Item name="description" label={t('agent.field.description')}>
            <TextArea rows={2} placeholder={t('agent.field.descriptionPlaceholder')} />
          </Form.Item>

          <Space style={{ width: '100%' }} size={16}>
            <Form.Item
              name="mode"
              label={t('agent.field.mode')}
              rules={[{ required: true }]}
              style={{ flex: 1 }}
            >
              <Select>
                <Select.Option value="primary">{t('agent.mode.primary')}</Select.Option>
                <Select.Option value="subagent">{t('agent.mode.subagent')}</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="maxIterations" label={t('agent.field.maxIterations')}>
              <InputNumber min={1} max={50} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="enabled" label={t('agent.field.enabled')} valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>

          <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
            {t('agent.field.modelHint')}
          </Paragraph>
          <Space style={{ width: '100%' }} size={16}>
            <Form.Item name="modelProvider" label={t('agent.field.modelProvider')} style={{ flex: 1 }}>
              <Select
                allowClear
                placeholder={t('agent.field.modelProviderInherit')}
                onChange={(val) => {
                  setSelectedProvider(val || '');
                  form.setFieldValue('modelName', undefined);
                }}
              >
                {availableProviders.map(p => (
                  <Select.Option key={p.key} value={p.key} disabled={!p.configured}>
                    {BUILTIN_PROVIDERS[p.key]?.label || p.key}
                    {!p.configured && ` (${t('agent.field.providerNotConfigured')})`}
                  </Select.Option>
                ))}
              </Select>
            </Form.Item>
            <Form.Item name="modelName" label={t('agent.field.modelName')} style={{ flex: 1 }}>
              <AutoComplete
                options={getModelOptions(selectedProvider)}
                placeholder={t('agent.field.modelNamePlaceholder')}
                allowClear
              />
            </Form.Item>
          </Space>

          <Form.Item label={t('agent.field.permissions')}>
            <div style={{ border: `1px solid ${token.colorBorderSecondary}`, borderRadius: 8, padding: '12px 16px' }}>
              <div style={{ marginBottom: 8, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontWeight: 500 }}>{t('agent.field.permAllowAll')}</span>
                <Switch
                  checked={permAllowAll}
                  onChange={(checked) => {
                    setPermAllowAll(checked);
                    if (checked) setPermSelectedTools([]);
                  }}
                />
              </div>
              {!permAllowAll && (
                <>
                  <Divider style={{ margin: '8px 0' }} />
                  <Checkbox.Group
                    value={permSelectedTools}
                    onChange={(vals) => setPermSelectedTools(vals as string[])}
                    style={{ width: '100%' }}
                  >
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                      {availableTools.map(tool => (
                        <Tooltip key={tool.name} title={tool.description}>
                          <Checkbox value={tool.name}>{tool.name}</Checkbox>
                        </Tooltip>
                      ))}
                    </div>
                  </Checkbox.Group>
                </>
              )}
            </div>
          </Form.Item>

          <Form.Item
            name="systemPrompt"
            label={t('agent.field.systemPrompt')}
            rules={[{ required: true, message: t('agent.field.systemPromptRequired') }]}
          >
            <TextArea rows={6} placeholder={t('agent.field.systemPromptPlaceholder')} />
          </Form.Item>

          <Form.Item>
            <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
              <Button onClick={() => setModalOpen(false)}>{t('common.cancel')}</Button>
              <Button type="primary" htmlType="submit">
                {editingAgent ? t('common.save') : t('agent.create')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default AgentConfig;
