import { useCallback, useEffect, useState } from 'react';
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
import { CheckCircleOutlined, CloseCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ColumnsType } from 'antd/es/table';
import { decideWorkflowApproval, getPendingWorkflowApprovals } from './api';
import type { ApprovalStatus, WorkflowApproval } from './types';
import { formatDateTime } from '../../utils/format';
import './workflow.css';

const { Title, Text } = Typography;

/** 工作流审批中心（仿 pages/ai/approval.tsx：待我审批 + 通过/拒绝） */
const WorkflowApprovalsPage: React.FC = () => {
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const [loading, setLoading] = useState(false);
  const [approvals, setApprovals] = useState<WorkflowApproval[]>([]);
  const [detail, setDetail] = useState<WorkflowApproval | null>(null);

  const fetchApprovals = useCallback(() => {
    setLoading(true);
    getPendingWorkflowApprovals()
      .then((res) => {
        if (res.code === 200 && res.data) setApprovals(res.data);
        else message.error(res.message || t('workflow.approval.fetchError'));
      })
      .catch(() => message.error(t('workflow.approval.fetchError')))
      .finally(() => setLoading(false));
  }, [t]);

  useEffect(() => {
    fetchApprovals();
    const timer = window.setInterval(fetchApprovals, 15000);
    return () => window.clearInterval(timer);
  }, [fetchApprovals]);

  const decide = useCallback(
    async (approval: WorkflowApproval, decision: 'approved' | 'rejected') => {
      const res = await decideWorkflowApproval(approval.id, { decision });
      if (res.code === 200) {
        message.success(decision === 'approved' ? t('workflow.approval.approveSuccess') : t('workflow.approval.rejectSuccess'));
        fetchApprovals();
      } else if (res.code === 409) {
        message.warning(t('workflow.approval.decided'));
      } else {
        message.error(res.message || t('workflow.approval.decideFailed'));
      }
    },
    [fetchApprovals, t]
  );

  const columns: ColumnsType<WorkflowApproval> = [
    {
      title: t('workflow.approval.col.title'),
      dataIndex: 'title',
      key: 'title',
      ellipsis: true,
      render: (title: string, record) => (
        <Space direction="vertical" size={0}>
          <Text strong style={{ color: token.colorText }}>{title}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {record.workflowName || `#${record.workflowId ?? '-'}`} · {record.nodeKey}
          </Text>
        </Space>
      ),
    },
    {
      title: t('workflow.approval.col.nodeType'),
      dataIndex: 'nodeType',
      key: 'nodeType',
      width: 110,
      render: (type: string) => t(`workflow.node.type.${type}`, type),
    },
    {
      title: t('workflow.approval.col.risk'),
      dataIndex: 'riskLevel',
      key: 'riskLevel',
      width: 110,
      render: (level: string | null) =>
        level ? (
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
          <Button type="link" size="small" onClick={() => setDetail(record)}>
            {t('common.detail')}
          </Button>
          <Popconfirm
            title={t('workflow.approval.confirmApprove')}
            description={t('workflow.approval.confirmApproveDesc')}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
            onConfirm={() => void decide(record, 'approved')}
          >
            <Button type="primary" size="small" icon={<CheckCircleOutlined />}>
              {t('workflow.approval.approve')}
            </Button>
          </Popconfirm>
          <Popconfirm
            title={t('workflow.approval.confirmReject')}
            description={t('workflow.approval.confirmRejectDesc')}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
            okButtonProps={{ danger: true }}
            onConfirm={() => void decide(record, 'rejected')}
          >
            <Button danger size="small" icon={<CloseCircleOutlined />}>
              {t('workflow.approval.reject')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} style={{ margin: 0, color: token.colorText }}>
          {t('workflow.approval.title')}
        </Title>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={fetchApprovals}>
          {t('common.refresh')}
        </Button>
      </div>

      <Card style={{ borderRadius: 12 }} styles={{ body: { padding: 0 } }}>
        <Table<WorkflowApproval>
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
        open={!!detail}
        onCancel={() => setDetail(null)}
        footer={null}
        width={640}
      >
        {detail && (
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
                '-'
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
                  <Button danger onClick={() => { void decide(detail, 'rejected'); setDetail(null); }}>
                    {t('workflow.approval.reject')}
                  </Button>
                </Space>
              </Descriptions.Item>
            )}
          </Descriptions>
        )}
      </Modal>
    </div>
  );
};

export default WorkflowApprovalsPage;
