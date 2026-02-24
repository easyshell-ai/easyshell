import { useState, useEffect, useCallback } from 'react';
import {
  Table,
  Card,
  Tag,
  Button,
  Typography,
  Space,
  Popconfirm,
  message,
  Modal,
  theme,
  Spin,
} from 'antd';
import {
  ReloadOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getPendingApprovals, approveExecution, rejectExecution } from '../../api/ai';
import type { Task } from '../../types';
import type { ColumnsType } from 'antd/es/table';
import { riskLevelMap, approvalStatusMap } from '../../utils/status';
import { formatTime } from '../../utils/format';

const { Title } = Typography;

const AiApproval: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [scriptModal, setScriptModal] = useState<{ visible: boolean; content: string; name: string }>({
    visible: false,
    content: '',
    name: '',
  });

  const fetchTasks = useCallback(() => {
    setLoading(true);
    getPendingApprovals()
      .then((res) => {
        if (res.code === 200 && res.data) {
          setTasks(res.data);
        }
      })
      .catch(() => message.error(t('approval.fetchError')))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchTasks();
    const interval = setInterval(fetchTasks, 10000);
    return () => clearInterval(interval);
  }, [fetchTasks]);

  const handleApprove = async (taskId: string) => {
    try {
      const res = await approveExecution(taskId);
      if (res.code === 200) {
        message.success(t('approval.approveSuccess'));
        fetchTasks();
      } else {
        message.error(res.message || t('approval.approveError'));
      }
    } catch {
      message.error(t('approval.approveError'));
    }
  };

  const handleReject = async (taskId: string) => {
    try {
      const res = await rejectExecution(taskId);
      if (res.code === 200) {
        message.success(t('approval.rejectSuccess'));
        fetchTasks();
      } else {
        message.error(res.message || t('approval.rejectError'));
      }
    } catch {
      message.error(t('approval.rejectError'));
    }
  };

  const columns: ColumnsType<Task> = [
    {
      title: t('approval.col.taskName'),
      dataIndex: 'name',
      key: 'name',
      ellipsis: true,
      width: 240,
    },
    {
      title: t('approval.col.scriptContent'),
      dataIndex: 'scriptContent',
      key: 'scriptContent',
      ellipsis: true,
      width: 300,
      render: (text: string, record: Task) => (
        <Space>
          <code style={{ fontSize: 12 }}>{text?.length > 60 ? text.substring(0, 60) + '...' : text}</code>
          {text && text.length > 0 && (
            <Button
              type="link"
              size="small"
              icon={<EyeOutlined />}
              onClick={() => setScriptModal({ visible: true, content: text, name: record.name })}
            >
              {t('common.view')}
            </Button>
          )}
        </Space>
      ),
    },
    {
      title: t('approval.col.riskLevel'),
      dataIndex: 'riskLevel',
      key: 'riskLevel',
      width: 100,
      render: (level: string) =>
        level ? (
          <Tag color={riskLevelMap[level]?.color || 'default'}>
            {t(riskLevelMap[level]?.text || level)}
          </Tag>
        ) : (
          <Tag>{t('common.unknown')}</Tag>
        ),
    },
    {
      title: t('approval.col.approvalStatus'),
      dataIndex: 'approvalStatus',
      key: 'approvalStatus',
      width: 100,
      render: (status: string) =>
        status ? (
          <Tag color={approvalStatusMap[status]?.color || 'default'}>
            {t(approvalStatusMap[status]?.text || status)}
          </Tag>
        ) : (
          <Tag>-</Tag>
        ),
    },
    {
      title: t('common.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (text: string) => text ? formatTime(text) : '-',
    },
    {
      title: t('common.actions'),
      key: 'action',
      width: 200,
      render: (_: unknown, record: Task) => {
        if (record.approvalStatus !== 'pending') {
          return <Tag>{t(approvalStatusMap[record.approvalStatus || '']?.text || 'approval.processed')}</Tag>;
        }
        return (
          <Space>
            <Popconfirm
              title={t('approval.confirmApprove')}
              description={t('approval.confirmApproveDesc')}
              onConfirm={() => handleApprove(record.id)}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
            >
              <Button type="primary" size="small" icon={<CheckCircleOutlined />}>
                {t('approval.approve')}
              </Button>
            </Popconfirm>
            <Popconfirm
              title={t('approval.confirmReject')}
              description={t('approval.confirmRejectDesc')}
              onConfirm={() => handleReject(record.id)}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
              okButtonProps={{ danger: true }}
            >
              <Button danger size="small" icon={<CloseCircleOutlined />}>
                {t('approval.reject')}
              </Button>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} style={{ margin: 0, color: token.colorText }}>
          {t('approval.title')}
        </Title>
        <Button icon={<ReloadOutlined />} onClick={fetchTasks} loading={loading}>
          {t('common.refresh')}
        </Button>
      </div>

      {loading && tasks.length === 0 ? (
        <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 120 }}>
          <Spin size="large" />
        </div>
      ) : (
        <Card style={{ borderRadius: 12 }} styles={{ body: { padding: 0 } }}>
          <Table
            dataSource={tasks}
            columns={columns}
            rowKey="id"
            pagination={{
              pageSize: 20,
              showSizeChanger: true,
              showTotal: (total) => t('common.totalItems', { count: total }),
            }}
            locale={{ emptyText: t('approval.noPending') }}
          />
        </Card>
      )}

      <Modal
        title={`${t('approval.col.scriptContent')} - ${scriptModal.name}`}
        open={scriptModal.visible}
        onCancel={() => setScriptModal({ visible: false, content: '', name: '' })}
        footer={null}
        width={700}
      >
        <pre
          style={{
            background: token.colorBgLayout,
            padding: 16,
            borderRadius: 8,
            maxHeight: 400,
            overflow: 'auto',
            fontFamily: 'monospace',
            fontSize: 13,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all',
          }}
        >
          {scriptModal.content}
        </pre>
      </Modal>
    </div>
  );
};

export default AiApproval;
