import { useState, useEffect, useCallback } from 'react';
import {
  Card,
  Button,
  Table,
  Tag,
  Drawer,
  Typography,
  Spin,
  Tooltip,
  Space,
  message,
  theme,
} from 'antd';
import {
  EyeOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getInspectReports, getInspectReport } from '../../api/ai';
import type { AiInspectReport } from '../../types';
import { taskTypeMap, reportStatusMap } from '../../utils/status';
import { formatTime } from '../../utils/format';
import MarkdownContent from '../../components/MarkdownContent';

const { Title, Text } = Typography;

const AiReports: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [reports, setReports] = useState<AiInspectReport[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);
  const [drawerVisible, setDrawerVisible] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detail, setDetail] = useState<AiInspectReport | null>(null);

  const fetchReports = useCallback(() => {
    setLoading(true);
    getInspectReports(page, pageSize)
      .then((res) => {
        if (res.code === 200 && res.data) {
          setReports(res.data.content);
          setTotal(res.data.totalElements);
        }
      })
      .catch(() => message.error(t('reports.fetchError')))
      .finally(() => setLoading(false));
  }, [page, pageSize]);

  useEffect(() => {
    fetchReports();
  }, [fetchReports]);

  const handleViewDetail = async (id: number) => {
    setDrawerVisible(true);
    setDetailLoading(true);
    setDetail(null);
    try {
      const res = await getInspectReport(id);
      if (res.code === 200 && res.data) {
        setDetail(res.data);
      } else {
        message.error(res.message || t('reports.detailError'));
      }
    } catch {
      message.error(t('reports.detailError'));
    } finally {
      setDetailLoading(false);
    }
  };

  const columns = [
    {
      title: t('reports.col.taskName'),
      dataIndex: 'taskName',
      key: 'taskName',
      width: 200,
      render: (text: string) => <span style={{ fontWeight: 500 }}>{text}</span>,
    },
    {
      title: t('reports.col.taskType'),
      dataIndex: 'taskType',
      key: 'taskType',
      width: 120,
      render: (type: string) => {
        const info = taskTypeMap[type] || { color: 'default', label: type };
        return <Tag color={info.color}>{t(info.label)}</Tag>;
      },
    },
    {
      title: t('reports.col.targetSummary'),
      dataIndex: 'targetSummary',
      key: 'targetSummary',
      width: 200,
      ellipsis: true,
      render: (text: string) => (
        <Tooltip title={text}>
          <span>{text || '-'}</span>
        </Tooltip>
      ),
    },
    {
      title: t('reports.col.status'),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: string) => {
        const info = reportStatusMap[status] || { color: 'default', label: status };
        return <Tag color={info.color}>{t(info.label)}</Tag>;
      },
    },
    {
      title: t('reports.col.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      render: (time: string) => (
        <span style={{ color: token.colorTextSecondary, fontSize: 13 }}>{formatTime(time)}</span>
      ),
    },
    {
      title: t('reports.col.actions'),
      key: 'action',
      width: 100,
      render: (_: unknown, record: AiInspectReport) => (
        <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => handleViewDetail(record.id)}>
          {t('reports.detail.viewDetail')}
        </Button>
      ),
    },
  ];

  if (loading && reports.length === 0) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 120 }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} style={{ margin: 0, color: token.colorText }}>
          {t('reports.title')}
        </Title>
        <Button icon={<ReloadOutlined />} onClick={fetchReports}>
          {t('common.refresh')}
        </Button>
      </div>

      <Card style={{ borderRadius: 12 }} styles={{ body: { padding: 0 } }}>
        <Table
          dataSource={reports}
          columns={columns}
          rowKey="id"
          loading={loading}
          locale={{ emptyText: t('reports.noReports') }}
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (count) => t('common.total', { count }),
            onChange: (p, ps) => {
              setPage(p - 1);
              setPageSize(ps);
            },
          }}
        />
      </Card>

      <Drawer
        title={t('reports.detailTitle')}
        open={drawerVisible}
        onClose={() => setDrawerVisible(false)}
        width={700}
      >
        {detailLoading ? (
          <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
            <Spin size="large" />
          </div>
        ) : detail ? (
          <div>
            <div style={{ marginBottom: 20 }}>
              <Space size={12} style={{ marginBottom: 12 }}>
                <Text strong style={{ fontSize: 16 }}>{detail.taskName}</Text>
                <Tag color={(taskTypeMap[detail.taskType] || { color: 'default' }).color}>
                  {t((taskTypeMap[detail.taskType] || { label: detail.taskType }).label)}
                </Tag>
                <Tag color={(reportStatusMap[detail.status] || { color: 'default' }).color}>
                  {t((reportStatusMap[detail.status] || { label: detail.status }).label)}
                </Tag>
              </Space>
              <div style={{ color: token.colorTextSecondary, fontSize: 13 }}>
                <div>{t('reports.detail.target')}: {detail.targetSummary || '-'}</div>
                <div>{t('reports.detail.createdAt')}: {formatTime(detail.createdAt)}</div>
              </div>
            </div>

            <div style={{ marginBottom: 20 }}>
              <Title level={5} style={{ color: token.colorText, marginBottom: 8 }}>
                {t('reports.detail.scriptOutput')}
              </Title>
              <pre
                style={{
                  background: token.colorFillTertiary,
                  border: `1px solid ${token.colorBorderSecondary}`,
                  borderRadius: 8,
                  padding: 16,
                  maxHeight: 400,
                  overflow: 'auto',
                  fontSize: 12,
                  fontFamily: 'monospace',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                  color: token.colorText,
                  margin: 0,
                }}
              >
                {detail.scriptOutput || t('reports.detail.noOutput')}
              </pre>
            </div>

            <div>
              <Title level={5} style={{ color: token.colorText, marginBottom: 8 }}>
                {t('reports.detail.aiAnalysis')}
              </Title>
              {detail.aiAnalysis ? (
                <div
                  style={{
                    background: token.colorBgContainer,
                    border: `1px solid ${token.colorBorderSecondary}`,
                    borderRadius: 8,
                    padding: 16,
                    lineHeight: 1.8,
                    color: token.colorText,
                  }}
                >
                  <MarkdownContent content={detail.aiAnalysis} />
                </div>
              ) : (
                <Text type="secondary">{t('reports.detail.noAnalysis')}</Text>
              )}
            </div>
          </div>
        ) : null}
      </Drawer>
    </div>
  );
};

export default AiReports;
