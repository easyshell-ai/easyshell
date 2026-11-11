import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tag,
  Typography,
  message,
  theme,
} from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, ReloadOutlined, SafetyOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ColumnsType } from 'antd/es/table';
import { decideWorkflowApproval, getPendingWorkflowApprovals } from './api';
import type { ApprovalStatus, WorkflowApproval } from './types';
import { formatDateTime } from '../../utils/format';
import PageShell from '../../layouts/PageShell';
import './workflow.css';

const { Text } = Typography;
type ApprovalItem = WorkflowApproval & { canApprove?: boolean; canReject?: boolean };
const isRestricted = (item: ApprovalItem) => item.canApprove === false || !item.title || !item.nodeType || !item.runId;

/** 工作流审批中心（仿 pages/ai/approval.tsx：待我审批 + 通过/拒绝） */
const WorkflowApprovalsPage: React.FC = () => {
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const [loading, setLoading] = useState(false);
  const [approvals, setApprovals] = useState<ApprovalItem[]>([]);
  const [detail, setDetail] = useState<ApprovalItem | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const generation = useRef(0);

  const fetchApprovals = useCallback(() => {
    const current = ++generation.current;
    setLoading(true);
    getPendingWorkflowApprovals()
      .then((res) => {
        if (current !== generation.current) return;
        if (res.code === 200 && res.data) {
          const items = res.data;
          setApprovals(items);
          setDetail((previous) => previous ? items.find((item) => item.id === previous.id) ?? null : null);
          setUnavailable(false);
        } else {
          setApprovals([]);
          setDetail(null);
          setUnavailable(true);
        }
      })
      .catch(() => { if (current === generation.current) { setApprovals([]); setDetail(null); setUnavailable(true); } })
      .finally(() => { if (current === generation.current) setLoading(false); });
  }, [t]);

  useEffect(() => {
    fetchApprovals();
    const timer = window.setInterval(fetchApprovals, 15000);
    return () => { generation.current++; window.clearInterval(timer); };
  }, [fetchApprovals]);

  const decide = useCallback(
    async (approval: ApprovalItem, decision: 'approved' | 'rejected') => {
      if (decision === 'approved' && isRestricted(approval) || decision === 'rejected' && approval.canReject === false) return;
      try {
      const res = await decideWorkflowApproval(approval.id, { decision });
      if (res.code === 200) {
        message.success(decision === 'approved' ? t('workflow.approval.approveSuccess') : t('workflow.approval.rejectSuccess'));
        fetchApprovals();
      } else if (res.code === 409) {
        message.warning(t('workflow.approval.decided'));
      } else if (res.code === 403) {
        generation.current++;
        setLoading(false);
        setApprovals([]);
        setDetail(null);
        setUnavailable(true);
      } else {
        message.error(res.message || t('workflow.approval.decideFailed'));
      }
      } catch {
        generation.current++;
        setLoading(false);
        setApprovals([]);
        setDetail(null);
        setUnavailable(true);
      }
    },
    [fetchApprovals, t]
  );

  const columns: ColumnsType<ApprovalItem> = [
    {
      title: t('workflow.approval.col.title'),
      dataIndex: 'title',
      key: 'title',
      ellipsis: true,
      render: (title: string, record) => (
        <Space direction="vertical" size={0}>
          {isRestricted(record) ? <Text>{t('workflow.approval.restrictedReason')}</Text> : <>
          <Text strong style={{ color: token.colorText }}>{title}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {record.workflowName || `#${record.workflowId ?? '-'}`} · {record.nodeKey}
          </Text>
          </>}
        </Space>
      ),
    },
    {
      title: t('workflow.approval.col.nodeType'),
      dataIndex: 'nodeType',
      key: 'nodeType',
      width: 110,
      render: (type: string, record) => isRestricted(record) ? '-' : t(`workflow.node.type.${type}`, type),
    },
    {
      title: t('workflow.approval.col.risk'),
      dataIndex: 'riskLevel',
      key: 'riskLevel',
      width: 110,
      render: (level: string | null, record) =>
        !isRestricted(record) && level ? (
          <Tag color={level === 'BANNED' ? 'purple' : level === 'HIGH' ? 'red' : level === 'MEDIUM' ? 'orange' : 'green'}>
            {level}
          </Tag>
        ) : (
          '-'
        ),
    },
    {
      title: t('common.status'),
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status: ApprovalStatus) => <Tag color={status === 'pending' ? 'orange' : 'default'}>{t(`workflow.approval.status.${status}`)}</Tag>,
    },
    {
      title: t('workflow.approval.col.expiresAt'),
      dataIndex: 'expiresAt',
      key: 'expiresAt',
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
      width: 200,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" disabled={isRestricted(record)} onClick={() => setDetail(record)}>
            {t('common.detail')}
          </Button>
          <Popconfirm
            disabled={isRestricted(record)}
            title={t('workflow.approval.confirmApprove')}
            description={t('workflow.approval.confirmApproveDesc')}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
            onConfirm={() => void decide(record, 'approved')}
          >
            <Button type="primary" size="small" disabled={isRestricted(record)} icon={<CheckCircleOutlined />}>
              {t('workflow.approval.approve')}
            </Button>
          </Popconfirm>
          <Popconfirm
            disabled={record.canReject === false}
            title={t('workflow.approval.confirmReject')}
            description={t('workflow.approval.confirmRejectDesc')}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
            okButtonProps={{ danger: true }}
            onConfirm={() => void decide(record, 'rejected')}
          >
            <Button danger size="small" disabled={record.canReject === false} icon={<CloseCircleOutlined />}>
              {t('workflow.approval.reject')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <PageShell
      title={t('workflow.approval.title')}
      icon={<SafetyOutlined />}
      extra={
        <Button icon={<ReloadOutlined />} loading={loading} onClick={fetchApprovals}>
          {t('common.refresh')}
        </Button>
      }
    >
      {unavailable && <Alert type="warning" showIcon message={t('workflow.approval.dataUnavailable')} />}
      <Card styles={{ body: { padding: 0 } }}>
        <Table<ApprovalItem>
          columns={columns}
          dataSource={approvals}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1100 }}
          pagination={{ pageSize: 20, showTotal: (total) => t('common.totalItems', { count: total }) }}
          locale={{ emptyText: t('workflow.approval.empty') }}
        />
      </Card>

      <Modal
        title={t('workflow.approval.detailTitle')}
        open={!!detail && !isRestricted(detail)}
        onCancel={() => setDetail(null)}
        footer={null}
        width={640}
      >
        {detail && !isRestricted(detail) && (
          <Descriptions size="small" column={1}>
            <Descriptions.Item label={t('workflow.approval.col.title')}>{detail.title}</Descriptions.Item>
            <Descriptions.Item label={t('workflow.run.col.nodeKey')}>{detail.nodeKey}</Descriptions.Item>
            <Descriptions.Item label={t('workflow.run.col.id')}>{detail.runId}</Descriptions.Item>
            <Descriptions.Item label={t('workflow.approval.col.risk')}>
              {detail.riskLevel || '-'}
              {detail.riskMatches ? ` · ${detail.riskMatches}` : ''}
            </Descriptions.Item>
            <Descriptions.Item label={t('workflow.approval.detail')}>
              {detail.detail ? (
                <pre className="wf-inline-json">{JSON.stringify(detail.detail, null, 2)}</pre>
              ) : (
                t('workflow.approval.dataUnavailable')
              )}
            </Descriptions.Item>
            {detail.status === 'pending' && (
              <Descriptions.Item label={t('common.actions')}>
                <Alert
                  type="info"
                  showIcon
                  message={t('workflow.approval.auditHint')}
                  style={{ marginBottom: 12 }}
                />
                <Space>
                  <Button type="primary" onClick={() => { void decide(detail, 'approved'); setDetail(null); }}>
                    {t('workflow.approval.approve')}
                  </Button>
                  <Button danger disabled={detail.canReject === false} onClick={() => { void decide(detail, 'rejected'); setDetail(null); }}>
                    {t('workflow.approval.reject')}
                  </Button>
                </Space>
              </Descriptions.Item>
            )}
          </Descriptions>
        )}
      </Modal>
    </PageShell>
  );
};

export default WorkflowApprovalsPage;
