import { useEffect, useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Card, Table, Button, Modal, Form, Input, Select, Switch, Popconfirm, Tag, Space, message, Tabs, Typography, theme,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { PlusOutlined, EditOutlined, DeleteOutlined, FileTextOutlined, BookOutlined, EyeOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { getScriptList, getScriptTemplates, getUserScripts, createScript, updateScript, deleteScript } from '../../api/script';
import type { Script, ScriptRequest } from '../../types';

const { TextArea } = Input;

const scriptTypeColors: Record<string, string> = {
  bash: 'green',
  sh: 'blue',
  python: 'orange',
};

const ScriptPage: React.FC = () => {
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const [userScripts, setUserScripts] = useState<Script[]>([]);
  const [templates, setTemplates] = useState<Script[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingScript, setEditingScript] = useState<Script | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [activeTab, setActiveTab] = useState('user');
  const [form] = Form.useForm<ScriptRequest>();
  const [viewScript, setViewScript] = useState<Script | null>(null);

  const fetchData = useCallback(() => {
    setLoading(true);
    Promise.all([getUserScripts(), getScriptTemplates()])
      .then(([userRes, templateRes]) => {
        if (userRes.code === 200) setUserScripts(userRes.data || []);
        if (templateRes.code === 200) setTemplates(templateRes.data || []);
      })
      .catch(() => {
        getScriptList().then((res) => {
          if (res.code === 200) {
            const all = res.data || [];
            setUserScripts(all.filter((s) => !s.isTemplate));
            setTemplates(all.filter((s) => s.isTemplate));
          }
        });
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleCreate = () => {
    setEditingScript(null);
    form.resetFields();
    form.setFieldsValue({ scriptType: 'bash', isPublic: false });
    setModalOpen(true);
  };

  const handleEdit = (record: Script) => {
    setEditingScript(record);
    form.setFieldsValue({
      name: record.name,
      description: record.description,
      content: record.content,
      scriptType: record.scriptType,
      isPublic: record.isPublic,
    });
    setModalOpen(true);
  };

  const handleDelete = (id: number) => {
    deleteScript(id).then((res) => {
      if (res.code === 200) {
        message.success(t('common.deleteSuccess'));
        fetchData();
      } else {
        message.error(res.message || t('common.deleteFailed'));
      }
    });
  };

  const handleSubmit = () => {
    form.validateFields().then((values) => {
      setSubmitting(true);
      const action = editingScript
        ? updateScript(editingScript.id, values)
        : createScript(values);

      action
        .then((res) => {
          if (res.code === 200) {
            message.success(editingScript ? t('common.updateSuccess') : t('common.createSuccess'));
            setModalOpen(false);
            fetchData();
          } else {
            message.error(res.message || t('common.operationFailed'));
          }
        })
        .finally(() => setSubmitting(false));
    });
  };

  const getColumns = (isTemplate: boolean): ColumnsType<Script> => [
    {
      title: t('script.name'), dataIndex: 'name', key: 'name', width: 200,
      render: (name: string, record) => (
        <Space>
          {record.isTemplate && <Tag color="gold"><BookOutlined /> {t('script.template')}</Tag>}
          {name}
        </Space>
      ),
    },
    {
      title: t('script.type'), dataIndex: 'scriptType', key: 'scriptType', width: 100,
      render: (type: string) => <Tag color={scriptTypeColors[type] || 'default'}>{type}</Tag>,
    },
    { title: t('script.description'), dataIndex: 'description', key: 'description', ellipsis: true },
    {
      title: t('script.public'), dataIndex: 'isPublic', key: 'isPublic', width: 80,
      render: (val: boolean) => (val ? <Tag color="blue">{t('common.yes')}</Tag> : <Tag>{t('common.no')}</Tag>),
    },
    { title: t('script.version'), dataIndex: 'version', key: 'version', width: 80 },
    { title: t('script.creator'), dataIndex: 'createdBy', key: 'createdBy', width: 100 },
    {
      title: t('common.updateTime'), dataIndex: 'updatedAt', key: 'updatedAt', width: 170,
      render: (val: string) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
    ...(!isTemplate ? [{
      title: t('common.actions'), key: 'action', width: 140, fixed: 'right' as const,
      render: (_: unknown, record: Script) => (
        <Space>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)}>{t('common.edit')}</Button>
          <Popconfirm title={t('script.confirmDelete')} onConfirm={() => handleDelete(record.id)} okText={t('common.confirm')} cancelText={t('common.cancel')}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>{t('common.delete')}</Button>
          </Popconfirm>
        </Space>
      ),
    }] : [{
      title: t('common.actions'), key: 'action', width: 100, fixed: 'right' as const,
      render: (_: unknown, record: Script) => (
        <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => setViewScript(record)}>{t('common.view')}</Button>
      ),
    }]),
  ];

  return (
    <>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography.Title level={4} style={{ margin: 0, color: token.colorText }}>
          <FileTextOutlined style={{ marginRight: 8, color: token.colorPrimary }} />{t('script.management')}
        </Typography.Title>
        {activeTab === 'user' && (
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>{t('script.create')}</Button>
        )}
      </div>

      <Card style={{ borderRadius: 12 }} styles={{ body: { padding: 0 } }}>
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: 'user',
              label: <><FileTextOutlined /> {t('script.myScripts')} ({userScripts.length})</>,
              children: (
                <Table<Script>
                  columns={getColumns(false)}
                  dataSource={userScripts}
                  rowKey="id"
                  loading={loading}
                  scroll={{ x: 1100 }}
                  pagination={{ pageSize: 20 }}
                />
              ),
            },
            {
              key: 'template',
              label: <><BookOutlined /> {t('script.templates')} ({templates.length})</>,
              children: (
                <Table<Script>
                  columns={getColumns(true)}
                  dataSource={templates}
                  rowKey="id"
                  loading={loading}
                  scroll={{ x: 1100 }}
                  pagination={{ pageSize: 20 }}
                />
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title={editingScript ? t('script.editScript') : t('script.createScript')}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        confirmLoading={submitting}
        width={700}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="name" label={t('script.scriptName')} rules={[{ required: true, message: t('script.pleaseInputName') }]}>
            <Input placeholder={t('script.pleaseInputName')} />
          </Form.Item>
          <Form.Item name="description" label={t('script.description')}>
            <Input placeholder={t('script.pleaseInputDescription')} />
          </Form.Item>
          <Form.Item name="content" label={t('script.content')} rules={[{ required: true, message: t('script.pleaseInputContent') }]}>
            <TextArea rows={10} placeholder={"#!/bin/bash\necho 'Hello EasyShell'"} style={{ fontFamily: 'monospace', background: token.colorFillAlter, color: token.colorText, border: `1px solid ${token.colorBorderSecondary}` }} />
          </Form.Item>
          <Form.Item name="scriptType" label={t('script.scriptType')} rules={[{ required: true, message: t('script.pleaseSelectType') }]}>
            <Select
              options={[
                { value: 'bash', label: 'Bash' },
                { value: 'sh', label: 'Shell' },
                { value: 'python', label: 'Python' },
              ]}
            />
          </Form.Item>
          <Form.Item name="isPublic" label={t('script.publicScript')} valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={viewScript?.name || t('script.template')}
        open={!!viewScript}
        onCancel={() => setViewScript(null)}
        footer={<Button onClick={() => setViewScript(null)}>{t('common.close')}</Button>}
        width={700}
      >
        {viewScript && (
          <>
            <p style={{ color: token.colorTextSecondary, marginBottom: 12 }}>{viewScript.description}</p>
            <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              <Tag color={scriptTypeColors[viewScript.scriptType] || 'default'}>{viewScript.scriptType}</Tag>
              <Tag>v{viewScript.version}</Tag>
            </div>
            <pre style={{
              background: token.colorFillAlter,
              color: token.colorText,
              border: `1px solid ${token.colorBorderSecondary}`,
              borderRadius: 8,
              padding: 16,
              fontSize: 13,
              fontFamily: "'Cascadia Code', 'Fira Code', 'Consolas', monospace",
              maxHeight: 400,
              overflow: 'auto',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
              margin: 0,
            }}>
              {viewScript.content}
            </pre>
          </>
        )}
      </Modal>
    </>
  );
};

export default ScriptPage;
