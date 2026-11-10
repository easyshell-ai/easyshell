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
  PartitionOutlined,
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
  saveWorkflowDraft,
  triggerWorkflowRun,
  validateWorkflow,
  type WorkflowPageResult,
} from './api';
import type { ValidationIssue, WorkflowDefinition, WorkflowSummary } from './types';
import { TriggerModal } from './editor/modals';
import { PublishModal } from './editor/modals';
import { createStartAndEndNodes } from './graph';
import { templateById, WORKFLOW_TEMPLATES } from './editor/templates';
import { formatDateTime } from '../../utils/format';
import PageShell from '../../layouts/PageShell';
import './workflow.css';

const { Text } = Typography;

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
  const [statusFilter, setStatusFilter] = useState<'active' | 'draft' | 'published' | 'archived'>('active');
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createForm] = Form.useForm<{
    name: string;
    description?: string;
    category?: string;
    visibility?: 'private' | 'public';
    template?: string;
  }>();

  const [triggerTarget, setTriggerTarget] = useState<{ id: number; name: string; definition: WorkflowDefinition } | null>(null);
  const [triggerLoading, setTriggerLoading] = useState(false);
  const [publishTarget, setPublishTarget] = useState<PublishTarget | null>(null);
  const [publishIssues, setPublishIssues] = useState<ValidationIssue[]>([]);
  const [publishLoading, setPublishLoading] = useState(false);

  const fetchPage = useCallback(async (current = 0, search = keyword, status = statusFilter) => {
    setLoading(true);
    try {
      const res = await getWorkflowPage({ page: current, size: 20, keyword: search || undefined, status });
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
  }, [keyword, statusFilter, t]);

  useEffect(() => {
    void fetchPage(0, '', 'active');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleCreate = useCallback(async () => {
    const values = await createForm.validateFields();
    setCreating(true);
    try {
      // template 只是前端字段，不发给创建接口（未知字段会被服务端 DTO 拒绝/忽略）
      const { template: templateId, ...payload } = values;
      const res = await createWorkflow(payload);
      if (res.code === 200 && res.data) {
        const workflow = res.data;
        // 新建后立刻落一份草稿：默认 start+end，或用户选的模板。
        // 失败仍进入编辑器（编辑器有空态引导/补全横幅），只是给一次警告。
        const template = templateById(templateId);
        const { start, end } = createStartAndEndNodes();
        const definition: WorkflowDefinition = template
          ? template.build()
          : {
              schemaVersion: 1,
              defaultOnUpstreamFailure: 'skip',
              onRejection: 'abort',
              variables: {},
              nodes: [
                { ...start, name: t('workflow.node.type.start') },
                { ...end, name: t('workflow.node.type.end') },
              ],
              edges: [],
            };
        try {
          const seeded = await saveWorkflowDraft(workflow.id, { definition, baseVersion: 0 });
          if (seeded.code !== 200) message.warning(t('workflow.list.seedFailed'));
        } catch {
          message.warning(t('workflow.list.seedFailed'));
        }
        message.success(t('workflow.list.created'));
        setCreateOpen(false);
        createForm.resetFields();
        navigate(`/workflow/editor/${workflow.id}`);
      } else {
        message.error(res.message || t('workflow.list.createFailed'));
      }
    } finally {
      setCreating(false);
    }
  }, [createForm, navigate, t]);

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
      // 列表页没有编辑器状态，先拉详情取服务端草稿 definition——预校验的请求体
      // 不能为空（服务端对空 body 返回 400，而不是当成"无 issues"放行）。
      const detailRes = await getWorkflowDetail(workflow.id);
      if (detailRes.code !== 200 || !detailRes.data) {
        message.error(detailRes.message || t('workflow.list.fetchFailed'));
        setPublishIssues([]);
        return;
      }
      const definition = detailRes.data.draft?.definition;
      if (!definition) {
        // 没有草稿行：发布本身会以 400「没有可发布的草稿」收场，这里不预置
        // "校验通过"来误导用户。
        setPublishIssues([]);
        return;
      }
      const res = await validateWorkflow(workflow.id, definition);
      setPublishIssues(res.code === 200 ? issuesOf(res.data) : []);
    } finally {
      setPublishLoading(false);
    }
  }, [t]);

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
    <PageShell
      title={t('workflow.list.title')}
      icon={<PartitionOutlined />}
      extra={
        <Space wrap>
          <Select
            value={statusFilter}
            onChange={(value) => {
              setStatusFilter(value);
              void fetchPage(0, keyword, value);
            }}
            options={[
              { value: 'active', label: t('workflow.list.statusFilter.active') },
              { value: 'draft', label: t('workflow.status.draft') },
              { value: 'published', label: t('workflow.status.published') },
              { value: 'archived', label: t('workflow.status.archived') },
            ]}
            style={{ width: 130 }}
            aria-label={t('workflow.list.statusFilter.label')}
          />
          <Input.Search
            allowClear
            placeholder={t('workflow.list.searchPlaceholder')}
            onSearch={(value) => {
              setKeyword(value);
              void fetchPage(0, value, statusFilter);
            }}
            style={{ width: 240 }}
          />
          <Button icon={<ReloadOutlined />} onClick={() => void fetchPage(page.number, keyword, statusFilter)} loading={loading}>
            {t('common.refresh')}
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)} data-testid="create-workflow">
            {t('workflow.list.create')}
          </Button>
        </Space>
      }
    >
      <Card styles={{ body: { padding: 0 } }}>
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
        <Form form={createForm} layout="vertical" initialValues={{ visibility: 'private', template: '' }}>
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
          <Form.Item name="template" label={t('workflow.list.template')} extra={t('workflow.list.templateHint')}>
            <Select
              options={[
                { value: '', label: t('workflow.list.templateNone') },
                ...WORKFLOW_TEMPLATES.map((template) => ({
                  value: template.id,
                  label: t(`workflow.template.${template.id}.name`),
                })),
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
    </PageShell>
  );
};

export default WorkflowListPage;
