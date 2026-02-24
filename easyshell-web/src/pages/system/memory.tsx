import { useState, useEffect, useCallback } from 'react';
import {
  Card,
  Table,
  Button,
  Space,
  Tag,
  message,
  Popconfirm,
  Typography,
  Tooltip,
  theme,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, ClearOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getMemoryList, deleteMemory, clearAllMemory } from '../../api/memory';
import type { AiSessionSummary } from '../../types';

const { Title } = Typography;

const outcomeColorMap: Record<string, string> = {
  SUCCESS: 'green',
  PARTIAL: 'orange',
  FAILED: 'red',
};

const MemoryManagement: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const [data, setData] = useState<AiSessionSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  const fetchData = useCallback(() => {
    setLoading(true);
    getMemoryList(page, pageSize)
      .then((res) => {
        if (res.code === 200 && res.data) {
          setData(res.data.content || []);
          setTotal(res.data.totalElements || 0);
        }
      })
      .catch(() => message.error(t('memory.fetchError')))
      .finally(() => setLoading(false));
  }, [page, pageSize, t]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleDelete = async (id: number) => {
    try {
      const res = await deleteMemory(id);
      if (res.code === 200) {
        message.success(t('memory.deleteSuccess'));
        fetchData();
      } else {
        message.error(res.message || t('memory.deleteError'));
      }
    } catch {
      message.error(t('memory.deleteError'));
    }
  };

  const handleClearAll = async () => {
    try {
      const res = await clearAllMemory();
      if (res.code === 200) {
        message.success(t('memory.clearSuccess'));
        setPage(0);
        fetchData();
      } else {
        message.error(res.message || t('memory.deleteError'));
      }
    } catch {
      message.error(t('memory.deleteError'));
    }
  };

  const columns: ColumnsType<AiSessionSummary> = [
    {
      title: t('memory.col.id'),
      dataIndex: 'id',
      key: 'id',
      width: 60,
    },
    {
      title: t('memory.col.sessionId'),
      dataIndex: 'sessionId',
      key: 'sessionId',
      width: 140,
      render: (text: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{text}</span>
      ),
    },
    {
      title: t('memory.col.summary'),
      dataIndex: 'summary',
      key: 'summary',
      ellipsis: true,
      render: (text: string) => text || '-',
    },
    {
      title: t('memory.col.outcome'),
      dataIndex: 'outcome',
      key: 'outcome',
      width: 100,
      render: (outcome: string) => outcome ? (
        <Tag color={outcomeColorMap[outcome] || 'default'}>
          {t(`memory.outcome.${outcome}`, outcome)}
        </Tag>
      ) : '-',
    },
    {
      title: t('memory.col.hosts'),
      dataIndex: 'hostsInvolved',
      key: 'hostsInvolved',
      width: 150,
      ellipsis: true,
      render: (text: string) => text || '-',
    },
    {
      title: t('memory.col.tags'),
      dataIndex: 'tags',
      key: 'tags',
      width: 150,
      render: (text: string) => {
        if (!text) return '-';
        return (
          <Space size={2} wrap>
            {text.split(',').filter(Boolean).map((tag) => (
              <Tag key={tag} style={{ fontSize: 10, margin: 0 }}>{tag.trim()}</Tag>
            ))}
          </Space>
        );
      },
    },
    {
      title: t('memory.col.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (text: string) => text ? new Date(text).toLocaleString() : '-',
    },
    {
      title: t('memory.col.actions'),
      key: 'actions',
      width: 80,
      render: (_: unknown, record: AiSessionSummary) => (
        <Popconfirm
          title={t('memory.confirmDelete')}
          onConfirm={() => handleDelete(record.id)}
          okText={t('common.confirm')}
          cancelText={t('common.cancel')}
        >
          <Tooltip title={t('common.delete')}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />} />
          </Tooltip>
        </Popconfirm>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} style={{ margin: 0, color: token.colorText }}>
          {t('memory.title')}
        </Title>
        <Popconfirm
          title={t('memory.clearAllConfirm')}
          onConfirm={handleClearAll}
          okText={t('common.confirm')}
          cancelText={t('common.cancel')}
        >
          <Button danger icon={<ClearOutlined />}>
            {t('memory.clearAll')}
          </Button>
        </Popconfirm>
      </div>

      <Card style={{ borderRadius: 12 }} styles={{ body: { padding: 0 } }}>
        <Table<AiSessionSummary>
          columns={columns}
          dataSource={data}
          rowKey="id"
          loading={loading}
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showTotal: (total) => t('memory.total', { count: total }),
            onChange: (p, s) => {
              setPage(p - 1);
              setPageSize(s);
            },
          }}
        />
      </Card>
    </div>
  );
};

export default MemoryManagement;
