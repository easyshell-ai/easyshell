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
  Switch,
  message,
  Popconfirm,
  Typography,
  Tooltip,
  theme,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  EditOutlined,
  DeleteOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getSopList, updateSop, deleteSop, triggerSopExtraction } from '../../api/sop';
import type { AiSopTemplate, AiSopTemplateRequest } from '../../types';

const { Title } = Typography;
const { TextArea } = Input;

const SopManagement: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const [data, setData] = useState<AiSopTemplate[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingSop, setEditingSop] = useState<AiSopTemplate | null>(null);
  const [form] = Form.useForm();

  const fetchData = useCallback(() => {
    setLoading(true);
    getSopList(page, pageSize)
      .then((res) => {
        if (res.code === 200 && res.data) {
          setData(res.data.content || []);
          setTotal(res.data.totalElements || 0);
        }
      })
      .catch(() => message.error(t('sop.fetchError')))
      .finally(() => setLoading(false));
  }, [page, pageSize, t]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const openEdit = (sop: AiSopTemplate) => {
    setEditingSop(sop);
    form.setFieldsValue({
      title: sop.title,
      description: sop.description,
      stepsJson: sop.stepsJson,
      triggerPattern: sop.triggerPattern,
      category: sop.category,
      enabled: sop.enabled,
    });
    setModalOpen(true);
  };

  const handleSubmit = async (values: AiSopTemplateRequest) => {
    if (!editingSop) return;
    try {
      const res = await updateSop(editingSop.id, values);
      if (res.code === 200) {
        message.success(t('sop.updateSuccess'));
        setModalOpen(false);
        fetchData();
      } else {
        message.error(res.message || t('sop.updateError'));
      }
    } catch {
      message.error(t('sop.updateError'));
    }
  };

  const handleToggle = async (sop: AiSopTemplate) => {
    try {
      const res = await updateSop(sop.id, { enabled: !sop.enabled });
      if (res.code === 200) {
        fetchData();
      } else {
        message.error(res.message || t('sop.updateError'));
      }
    } catch {
      message.error(t('sop.updateError'));
    }
  };

  const handleDelete = async (id: number) => {
    try {
      const res = await deleteSop(id);
      if (res.code === 200) {
        message.success(t('sop.deleteSuccess'));
        fetchData();
      } else {
        message.error(res.message || t('sop.deleteError'));
      }
    } catch {
      message.error(t('sop.deleteError'));
    }
  };

  const handleExtract = async () => {
    try {
      const res = await triggerSopExtraction();
      if (res.code === 200) {
        message.success(t('sop.extractSuccess'));
      } else {
        message.error(res.message || t('sop.extractError'));
      }
    } catch {
      message.error(t('sop.extractError'));
    }
  };

  const columns: ColumnsType<AiSopTemplate> = [
    {
      title: t('sop.col.id'),
      dataIndex: 'id',
      key: 'id',
      width: 60,
    },
    {
      title: t('sop.col.title'),
      dataIndex: 'title',
      key: 'title',
      width: 200,
      ellipsis: true,
      render: (text: string) => (
        <span style={{ fontWeight: 500 }}>{text}</span>
      ),
    },
    {
      title: t('sop.col.category'),
      dataIndex: 'category',
      key: 'category',
      width: 120,
      render: (text: string) => text ? <Tag color="blue">{text}</Tag> : '-',
    },
    {
      title: t('sop.col.confidence'),
      dataIndex: 'confidence',
      key: 'confidence',
      width: 100,
      align: 'center',
      render: (val: number) => {
        const pct = Math.round((val || 0) * 100);
        const color = pct >= 80 ? 'green' : pct >= 50 ? 'orange' : 'default';
        return <Tag color={color}>{pct}%</Tag>;
      },
    },
    {
      title: t('sop.col.enabled'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (enabled: boolean, record: AiSopTemplate) => (
        <Switch
          checked={enabled}
          size="small"
          onChange={() => handleToggle(record)}
        />
      ),
    },
    {
      title: t('sop.col.usageCount'),
      dataIndex: 'usageCount',
      key: 'usageCount',
      width: 80,
      align: 'center',
    },
    {
      title: t('sop.col.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (text: string) => text ? new Date(text).toLocaleString() : '-',
    },
    {
      title: t('sop.col.actions'),
      key: 'actions',
      width: 120,
      render: (_: unknown, record: AiSopTemplate) => (
        <Space size="small">
          <Tooltip title={t('common.edit')}>
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => openEdit(record)}
            />
          </Tooltip>
          <Popconfirm
            title={t('sop.confirmDelete')}
            onConfirm={() => handleDelete(record.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Tooltip title={t('common.delete')}>
              <Button type="link" size="small" danger icon={<DeleteOutlined />} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} style={{ margin: 0, color: token.colorText }}>
          {t('sop.title')}
        </Title>
        <Button type="primary" icon={<ThunderboltOutlined />} onClick={handleExtract}>
          {t('sop.extract')}
        </Button>
      </div>

      <Card style={{ borderRadius: 12 }} styles={{ body: { padding: 0 } }}>
        <Table<AiSopTemplate>
          columns={columns}
          dataSource={data}
          rowKey="id"
          loading={loading}
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showTotal: (total) => t('sop.total', { count: total }),
            onChange: (p, s) => {
              setPage(p - 1);
              setPageSize(s);
            },
          }}
        />
      </Card>

      <Modal
        title={t('sop.editTitle')}
        open={modalOpen}
        onCancel={() => {
          setModalOpen(false);
          setEditingSop(null);
        }}
        footer={null}
        destroyOnClose
        width={640}
      >
        <Form form={form} layout="vertical" onFinish={handleSubmit}>
          <Form.Item
            name="title"
            label={t('sop.field.title')}
            rules={[{ required: true, message: t('sop.field.titleRequired') }]}
          >
            <Input />
          </Form.Item>

          <Form.Item name="description" label={t('sop.field.description')}>
            <TextArea rows={3} />
          </Form.Item>

          <Form.Item name="stepsJson" label={t('sop.field.stepsJson')}>
            <TextArea rows={5} placeholder={t('sop.field.stepsJsonPlaceholder')} style={{ fontFamily: 'monospace' }} />
          </Form.Item>

          <Form.Item name="triggerPattern" label={t('sop.field.triggerPattern')}>
            <Input placeholder={t('sop.field.triggerPatternPlaceholder')} />
          </Form.Item>

          <Space style={{ width: '100%' }} size={16}>
            <Form.Item name="category" label={t('sop.field.category')} style={{ flex: 1 }}>
              <Input placeholder={t('sop.field.categoryPlaceholder')} />
            </Form.Item>
            <Form.Item name="enabled" label={t('sop.field.enabled')} valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>

          <Form.Item>
            <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
              <Button onClick={() => setModalOpen(false)}>{t('common.cancel')}</Button>
              <Button type="primary" htmlType="submit">
                {t('common.save')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default SopManagement;
