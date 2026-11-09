import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Button, Card, Space, Table, Tag, Typography, message } from 'antd';
import { EyeOutlined, FieldTimeOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ColumnsType } from 'antd/es/table';
import { getWorkflowRuns } from '../api';
import type { PageResponse, RunStatus, WorkflowRun } from '../types';
import { formatDateTime } from '../../../utils/format';
import { runStatusMap } from '../status';
import PageShell from '../../../layouts/PageShell';
import '../workflow.css';

/** 运行列表（§6.1 GET /v1/workflow/runs，非 admin 只看自己的） */
const WorkflowRunListPage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const workflowId = searchParams.get('workflowId');

  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState<PageResponse<WorkflowRun>>({
    content: [], totalElements: 0, totalPages: 0, size: 20, number: 0,
  });

  const fetchRuns = useCallback(
    async (current = 0) => {
      setLoading(true);
      try {
        const res = await getWorkflowRuns({ page: current, size: 20, workflowId: workflowId ? Number(workflowId) : undefined });
        if (res.code === 200 && res.data) setPage(res.data);
        else message.error(res.message || t('workflow.run.fetchFailed'));
      } catch {
        message.error(t('workflow.run.fetchFailed'));
      } finally {
        setLoading(false);
      }
    },
    [workflowId, t]
  );

  useEffect(() => {
    void fetchRuns(0);
  }, [fetchRuns]);

  const columns: ColumnsType<WorkflowRun> = useMemo(
    () => [
      {
        title: t('workflow.run.col.id'),
        dataIndex: 'id',
        key: 'id',
        width: 220,
        ellipsis: true,
        render: (id: string) => <Typography.Text code style={{ fontSize: 12 }}>{id}</Typography.Text>,
      },
      { title: t('workflow.run.col.workflow'), dataIndex: 'workflowId', key: 'workflowId', width: 110 },
      {
        title: t('workflow.run.col.version'),
        key: 'version',
        width: 90,
        render: (_, record) => `v${record.versionNo}`,
      },
      {
        title: t('common.status'),
        dataIndex: 'status',
        key: 'status',
        width: 120,
        render: (status: RunStatus) => {
          const mapped = runStatusMap[status] || runStatusMap.pending;
          return <Tag color={mapped.color}>{t(mapped.text)}</Tag>;
        },
      },
      {
        title: t('workflow.run.col.trigger'),
        dataIndex: 'triggerType',
        key: 'triggerType',
        width: 100,
        render: (type: string) => t(`workflow.run.trigger.${type}`),
      },
      {
        title: t('workflow.run.col.currentNodes'),
        dataIndex: 'currentNodes',
        key: 'currentNodes',
        width: 200,
        ellipsis: true,
        render: (nodes: string[] | null) => (nodes && nodes.length > 0 ? nodes.join(', ') : '-'),
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
        width: 120,
        fixed: 'right',
        render: (_, record) => (
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => navigate(`/workflow/run/${record.id}`)}>
            {t('workflow.run.monitor')}
          </Button>
        ),
      },
    ],
    [t, navigate]
  );

  return (
    <PageShell
      title={t('workflow.run.title')}
      icon={<FieldTimeOutlined />}
      extra={
        <Space>
          {workflowId && <Tag color="blue">{t('workflow.run.filtered', { id: workflowId })}</Tag>}
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void fetchRuns(page.number)}>
            {t('common.refresh')}
          </Button>
        </Space>
      }
    >
      <Card style={{ borderRadius: 12 }} styles={{ body: { padding: 0 } }}>
        <Table<WorkflowRun>
          columns={columns}
          dataSource={page.content}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1100 }}
          pagination={{
            current: page.number + 1,
            pageSize: page.size,
            total: page.totalElements,
            showTotal: (total) => t('common.totalItems', { count: total }),
            onChange: (current) => void fetchRuns(current - 1),
          }}
          locale={{ emptyText: t('workflow.run.empty') }}
        />
      </Card>
    </PageShell>
  );
};

export default WorkflowRunListPage;
