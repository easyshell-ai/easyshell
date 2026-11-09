import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  CloudUploadOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { theme } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  archiveWorkflow,
  createWorkflow,
  issuesOf,
  getWorkflowDetail,
  getWorkflowPage,
  publishWorkflow,
  triggerWorkflowRun,
  validateWorkflow,
  type WorkflowPageResult,
} from './api';
import type { ValidationIssue, WorkflowDefinition, WorkflowSummary } from './types';
import { TriggerModal } from './editor/modals';
import { PublishModal } from './editor/modals';
import { formatDateTime } from '../../utils/format';
import './workflow.css';

const { Title, Text } = Typography;

interface PublishTarget {
  id: number;
  name: string;
}

const WorkflowListPage: React.FC = () => {
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const navigate = useNavigate();

  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState<WorkflowPageResult>({
    content: [], totalElements: 0, totalPages: 0, size: 20, number: 0,
  });
  const [keyword, setKeyword] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createForm] = Form.useForm<{ name: string; description?: string; category?: string; visibility?: 'private' | 'public' }>();

  const [triggerTarget, setTriggerTarget] = useState<{ id: number; name: string; definition: WorkflowDefinition } | null>(null);
  const [triggerLoading, setTriggerLoading] = useState(false);
  const [publishTarget, setPublishTarget] = useState<PublishTarget | null>(null);
  const [publishIssues, setPublishIssues] = useState<ValidationIssue[]>([]);
  const [publishLoading, setPublishLoading] = useState(false);

  const fetchPage = useCallback(async (current = 0, search = keyword) => {
    setLoading(true);
    try {
      const res = await getWorkflowPage({ page: current, size: 20, keyword: search || undefined });
      if (res.code === 200 && res.data) {
        setPage(res.data);
      } else if (res.code === 403) {
        message.error(t('workflow.error.403'));
      } else {
        message.error(res.message || t('workflow.list.fetchFailed'));
      }
    } catch {
      message.error(t('workflow.list.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [keyword, t]);

  useEffect(() => {
    void fetchPage(0, '');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleCreate = useCallback(async () => {
    const values = await createForm.validateFields();
    setCreating(true);
    try {
      const res = await createWorkflow(values);
      if (res.code === 200 && res.data) {
        message.success(t('workflow.list.created'));
        setCreateOpen(false);
        createForm.resetFields();
        navigate(`/workflow/editor/${res.data.id}`);
      } else {
        message.error(res.message || t('workflow.list.createFailed'));
      }
    } finally {
      setCreating(false);
    }
  }, [createForm, navigate]);

  const openTrigger = useCallback(
    async (workflow: WorkflowSummary) => {
      const res = await getWorkflowDetail(workflow.id);
      if (res.code !== 200 || !res.data) {
        message.error(res.message || t('workflow.list.fetchFailed'));
        return;
      }
      setTriggerTarget({
        id: workflow.id,
        name: workflow.name,
        definition: res.data.draft?.definition || {
          schemaVersion: 1,
          defaultOnUpstreamFailure: 'skip',
          onRejection: 'abort',
          variables: {},
          nodes: [],
          edges: [],
        },
      });
    },
    []
  );

  const handleTrigger = useCallback(
    async (inputs: Record<string, unknown>, idempotencyKey: string) => {
      if (!triggerTarget) return;
      setTriggerLoading(true);
      try {
        const res = await triggerWorkflowRun(triggerTarget.id, { input: inputs, idempotencyKey });
        if (res.code === 200 && res.data) {
          message.success(t('workflow.trigger.success'));
          setTriggerTarget(null);
          navigate(`/workflow/run/${res.data.id}`);
        } else if (res.code === 409) {
          message.warning(t('workflow.trigger.notPublished'));
        } else if (res.code === 429) {
          message.warning(t('workflow.trigger.concurrency'));
        } else {
          message.error(res.message || t('workflow.trigger.failed'));
        }
      } finally {
        setTriggerLoading(false);
      }
    },
    [triggerTarget, navigate]
  );

  const openPublish = useCallback(async (workflow: WorkflowSummary) => {
    setPublishTarget({ id: workflow.id, name: workflow.name });
    setPublishLoading(true);
    try {
      const res = await validateWorkflow(workflow.id);
      setPublishIssues(res.code === 200 ? issuesOf(res.data) : []);
    } finally {
      setPublishLoading(false);
    }
  }, []);

  const confirmPublish = useCallback(async () => {
    if (!publishTarget) return;
    setPublishLoading(true);
    try {
      const res = await publishWorkflow(publishTarget.id, {});
      if (res.code === 200) {
        message.success(t('workflow.publish.success'));
        setPublishTarget(null);
        void fetchPage(page.number, keyword);
      } else if (res.code === 422) {
        setPublishIssues(issuesOf(res.data));
      } else if (res.code === 449) {
        message.warning(t('workflow.publish.riskBlocked'));
      } else {
        message.error(res.message || t('workflow.publish.failed'));
      }
    } finally {
      setPublishLoading(false);
    }
  }, [publishTarget, fetchPage, page.number, keyword]);

  const columns: ColumnsType<WorkflowSummary> = useMemo(
    () => [
      {
        title: t('workflow.list.col.name'),
        dataIndex: 'name',
        key: 'name',
        ellipsis: true,
        render: (name: string, record) => (
          <Space direction="vertical" size={0}>
            <Text strong style={{ color: token.colorText }}>{name}</Text>
            {record.description && (
              <Text type="secondary" style={{ fontSize: 12 }}>{record.description}</Text>
            )}
          </Space>
        ),
      },
      {
        title: t('common.status'),
        dataIndex: 'status',
        key: 'status',
        width: 100,
        render: (status: WorkflowSummary['status']) => (
          <Tag color={status === 'published' ? 'green' : status === 'archived' ? 'default' : 'orange'}>
            {t(`workflow.status.${status}`)}
          </Tag>
        ),
      },
      {
        title: t('workflow.list.col.visibility'),
        dataIndex: 'visibility',
        key: 'visibility',
        width: 100,
        render: (visibility: WorkflowSummary['visibility']) => (
          <Tag>{t(`workflow.visibility.${visibility}`)}</Tag>
        ),
      },
      {
        title: t('workflow.list.col.category'),
        dataIndex: 'category',
        key: 'category',
        width: 120,
        render: (category: string | null) => category || '-',
      },
      {
        title: t('workflow.list.col.version'),
        dataIndex: 'currentVersion',
        key: 'currentVersion',
        width: 90,
        render: (version: number | null) => (version ? `v${version}` : '-'),
      },
      {
        title: t('workflow.list.col.runs'),
        key: 'runs',
        width: 140,
        render: (_, record) => (
          <Space size={4}>
            <Tag color="blue">{record.runCount}</Tag>
            <Text type="secondary">/</Text>
            <Tag color="green">{record.successCount}</Tag>
          </Space>
        ),
      },
      {
        title: t('workflow.list.col.lastRunAt'),
        dataIndex: 'lastRunAt',
        key: 'lastRunAt',
        width: 170,
        render: (value: string | null) => (value ? formatDateTime(value) : '-'),
      },
      {
        title: t('common.createdAt'),
        dataIndex: 'createdAt',
        key: 'createdAt',
        width: 170,
        render: (value: string) => (value ? formatDateTime(value) : '-'),
      },
      {
        title: t('common.actions'),
        key: 'action',
        width: 260,
        fixed: 'right',
        render: (_, record) => (
          <Space size={4} wrap>
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => navigate(`/workflow/editor/${record.id}`)}>
              {t('workflow.list.edit')}
            </Button>
            <Button type="link" size="small" icon={<PlayCircleOutlined />} onClick={() => void openTrigger(record)}>
              {t('workflow.trigger.action')}
            </Button>
            <Button type="link" size="small" icon={<CloudUploadOutlined />} onClick={() => void openPublish(record)}>
              {t('workflow.publish.action')}
            </Button>
            <Button type="link" size="small" onClick={() => navigate(`/workflow/run?workflowId=${record.id}`)}>
              {t('workflow.run.title')}
            </Button>
            <Popconfirm
              title={t('workflow.list.archiveConfirm')}
              onConfirm={async () => {
                const res = await archiveWorkflow(record.id);
                if (res.code === 200) {
                  message.success(t('workflow.list.archived'));
                  void fetchPage(page.number, keyword);
                } else {
                  message.error(res.message || t('workflow.list.archiveFailed'));
                }
              }}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
              okButtonProps={{ danger: true }}
            >
              <Button type="link" size="small" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          </Space>
        ),
      },
    ],
    [t, token.colorText, navigate, openTrigger, openPublish, fetchPage, page.number, keyword]
  );

  return (
    <div style={{ padding: 24 }}>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} style={{ margin: 0, color: token.colorText }}>
          {t('workflow.list.title')}
        </Title>
        <Space>
          <Input.Search
            allowClear
            placeholder={t('workflow.list.searchPlaceholder')}
            onSearch={(value) => {
              setKeyword(value);
              void fetchPage(0, value);
            }}
            style={{ width: 240 }}
          />
          <Button icon={<ReloadOutlined />} onClick={() => void fetchPage(page.number, keyword)} loading={loading}>
            {t('common.refresh')}
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)} data-testid="create-workflow">
            {t('workflow.list.create')}
          </Button>
        </Space>
      </div>

      <Card style={{ borderRadius: 12 }} styles={{ body: { padding: 0 } }}>
        <Table<WorkflowSummary>
          columns={columns}
          dataSource={page.content}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1200 }}
          pagination={{
            current: page.number + 1,
            pageSize: page.size,
            total: page.totalElements,
            showSizeChanger: false,
            showTotal: (total) => t('common.totalItems', { count: total }),
            onChange: (current) => void fetchPage(current - 1, keyword),
          }}
          locale={{ emptyText: t('workflow.list.empty') }}
        />
      </Card>

      <Modal
        title={t('workflow.list.create')}
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => void handleCreate()}
        confirmLoading={creating}
        okText={t('common.create')}
        cancelText={t('common.cancel')}
      >
        <Form form={createForm} layout="vertical" initialValues={{ visibility: 'private' }}>
          <Form.Item name="name" label={t('workflow.list.col.name')} rules={[{ required: true, message: t('workflow.list.nameRequired') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label={t('workflow.list.description')}>
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="category" label={t('workflow.list.col.category')}>
            <Input />
          </Form.Item>
          <Form.Item name="visibility" label={t('workflow.list.col.visibility')}>
            <Select
              options={[
                { value: 'private', label: t('workflow.visibility.private') },
                { value: 'public', label: t('workflow.visibility.public') },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>

      {triggerTarget && (
        <TriggerModal
          open={!!triggerTarget}
          definition={triggerTarget.definition}
          workflowName={triggerTarget.name}
          loading={triggerLoading}
          onCancel={() => setTriggerTarget(null)}
          onSubmit={(inputs, key) => void handleTrigger(inputs, key)}
        />
      )}

      {publishTarget && (
        <PublishModal
          open={!!publishTarget}
          issues={publishIssues}
          loading={publishLoading}
          onCancel={() => setPublishTarget(null)}
          onConfirm={() => void confirmPublish()}
          onRevalidate={() => void openPublish({ id: publishTarget.id, name: publishTarget.name } as WorkflowSummary)}
        />
      )}

      {page.content.length === 0 && !loading && (
        <Alert style={{ marginTop: 16 }} type="info" showIcon message={t('workflow.list.emptyHint')} />
      )}
    </div>
  );
};

export default WorkflowListPage;
