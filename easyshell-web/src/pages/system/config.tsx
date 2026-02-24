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
  message,
  Typography,
  Descriptions,
  theme,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  PlusOutlined,
  EditOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { useResponsive } from '../../hooks/useResponsive';
import {
  getSystemConfigList,
  saveSystemConfig,
} from '../../api/system';
import type { SystemConfigVO } from '../../types';

const { Title, Text } = Typography;

const groupColorMap: Record<string, string> = {
  agent: 'blue',
  task: 'green',
  system: 'purple',
};

const SystemConfig: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const { isMobile } = useResponsive();
  const [configs, setConfigs] = useState<SystemConfigVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingConfig, setEditingConfig] = useState<SystemConfigVO | null>(null);
  const [filterGroup, setFilterGroup] = useState<string | undefined>(undefined);
  const [form] = Form.useForm();

  const fetchConfigs = useCallback(() => {
    setLoading(true);
    getSystemConfigList(filterGroup)
      .then((res) => {
        if (res.code === 200) {
          setConfigs(res.data || []);
        }
      })
      .catch(() => message.error(t('config.fetchError')))
      .finally(() => setLoading(false));
  }, [filterGroup]);

  useEffect(() => {
    fetchConfigs();
  }, [fetchConfigs]);

  const handleSave = async (values: {
    configKey: string;
    configValue: string;
    description?: string;
    configGroup?: string;
  }) => {
    try {
      const res = await saveSystemConfig(values);
      if (res.code === 200) {
        message.success(editingConfig ? t('config.saveSuccess') : t('config.saveSuccess'));
        setModalOpen(false);
        setEditingConfig(null);
        form.resetFields();
        fetchConfigs();
      } else {
        message.error(res.message || t('config.saveError'));
      }
    } catch {
      message.error(t('config.saveError'));
    }
  };

  const openCreate = () => {
    setEditingConfig(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openEdit = (config: SystemConfigVO) => {
    setEditingConfig(config);
    form.setFieldsValue({
      configKey: config.configKey,
      configValue: config.configValue,
      description: config.description,
      configGroup: config.configGroup,
    });
    setModalOpen(true);
  };

  const agentConfigs = configs.filter((c) => c.configGroup === 'agent');

  const columns: ColumnsType<SystemConfigVO> = [
    {
      title: t('config.col.group'),
      dataIndex: 'configGroup',
      key: 'configGroup',
      width: 120,
      render: (group: string) => (
        <Tag color={groupColorMap[group] || 'default'}>
          {t(`config.group.${group}`, { defaultValue: group })}
        </Tag>
      ),
    },
    {
      title: t('config.col.key'),
      dataIndex: 'configKey',
      key: 'configKey',
      render: (text: string) => (
        <Text code style={{ color: token.colorText, fontSize: isMobile ? 12 : 14 }}>
          {text}
        </Text>
      ),
    },
    {
      title: t('config.col.value'),
      dataIndex: 'configValue',
      key: 'configValue',
      render: (text: string) => (
        <Text strong style={{ color: token.colorPrimary }}>
          {text}
        </Text>
      ),
    },
    {
      title: t('config.col.description'),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      responsive: ['md'],
    },
    {
      title: t('config.col.updatedAt'),
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 160,
      responsive: ['lg'],
      render: (v: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: t('config.col.actions'),
      key: 'actions',
      width: isMobile ? 100 : 150,
      fixed: isMobile ? 'right' : undefined,
      render: (_, record) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => openEdit(record)}
          >
            {!isMobile && t('common.edit')}
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ 
        marginBottom: 16, 
        display: 'flex', 
        justifyContent: 'space-between', 
        alignItems: 'center',
        flexWrap: isMobile ? 'wrap' : 'nowrap',
        gap: 8,
      }}>
        <Title level={isMobile ? 5 : 4} style={{ margin: 0, color: token.colorText }}>
          {t('config.title')}
        </Title>
        <Space wrap={isMobile} size={isMobile ? 'small' : 'middle'}>
          <Select
            allowClear
            placeholder={t('config.filterPlaceholder')}
            style={{ width: isMobile ? 120 : 150 }}
            size={isMobile ? 'small' : 'middle'}
            value={filterGroup}
            onChange={(v) => setFilterGroup(v)}
          >
            <Select.Option value="agent">{t('config.group.agent')}</Select.Option>
            <Select.Option value="task">{t('config.group.task')}</Select.Option>
            <Select.Option value="system">{t('config.group.system')}</Select.Option>
          </Select>
          <Button
            type="primary"
            size={isMobile ? 'small' : 'middle'}
            icon={<PlusOutlined />}
            onClick={openCreate}
          >
            {t('config.createConfig')}
          </Button>
        </Space>
      </div>

      {agentConfigs.length > 0 && !filterGroup && (
        <Card
          size="small"
          title={
            <Space>
              <SettingOutlined style={{ color: token.colorPrimary }} />
              <span style={{ color: token.colorText }}>{t('config.agentConfig')}</span>
            </Space>
          }
          style={{ ...{ borderRadius: 12 }, marginBottom: 16 }}
        >
          <Descriptions column={{ xs: 1, sm: 2, md: 4 }} size="small">
            {agentConfigs.map((c) => (
              <Descriptions.Item
                key={c.id}
                label={
                  <span style={{ color: token.colorTextSecondary }}>
                    {c.description || c.configKey}
                  </span>
                }
              >
                <Text strong style={{ color: token.colorPrimary }}>
                  {c.configValue}
                  {c.configKey.includes('interval') || c.configKey.includes('timeout')
                    ? ` ${t('common.seconds')}`
                    : ''}
                </Text>
              </Descriptions.Item>
            ))}
          </Descriptions>
        </Card>
      )}

      <Card style={{ borderRadius: 12 }} styles={{ body: { padding: 0 } }}>
        <Table<SystemConfigVO>
          columns={columns}
          dataSource={configs}
          rowKey="id"
          loading={loading}
          pagination={false}
          scroll={{ x: isMobile ? 500 : undefined }}
          size={isMobile ? 'small' : 'middle'}
        />
      </Card>

      <Modal
        title={editingConfig ? t('config.editConfig') : t('config.createConfig')}
        open={modalOpen}
        onCancel={() => {
          setModalOpen(false);
          setEditingConfig(null);
        }}
        footer={null}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item
            name="configKey"
            label={t('config.field.key')}
            rules={[{ required: true, message: t('config.field.keyRequired') }]}
          >
            <Input
              placeholder={t('config.field.keyPlaceholder')}
              disabled={!!editingConfig}
            />
          </Form.Item>
          <Form.Item
            name="configValue"
            label={t('config.field.value')}
            rules={[{ required: true, message: t('config.field.valueRequired') }]}
          >
            <Input placeholder={t('config.field.valuePlaceholder')} />
          </Form.Item>
          <Form.Item name="description" label={t('config.field.description')}>
            <Input placeholder={t('config.field.descriptionPlaceholder')} />
          </Form.Item>
          <Form.Item name="configGroup" label={t('config.field.group')}>
            <Select placeholder={t('config.field.groupPlaceholder')} allowClear>
              <Select.Option value="agent">{t('config.group.agent')}</Select.Option>
              <Select.Option value="task">{t('config.group.task')}</Select.Option>
              <Select.Option value="system">{t('config.group.system')}</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item>
            <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
              <Button onClick={() => setModalOpen(false)}>{t('common.cancel')}</Button>
              <Button
                type="primary"
                htmlType="submit"
              >
                {t('common.save')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default SystemConfig;
