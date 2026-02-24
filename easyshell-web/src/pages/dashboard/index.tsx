import { useEffect, useState } from 'react';
import { Row, Col, Card, Statistic, Spin, Table, Tag, Progress, Typography, theme, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DesktopOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  CodeOutlined,
  PlayCircleOutlined,
  DashboardOutlined,
  ReloadOutlined,
  WarningOutlined,
  CalendarOutlined,
  CheckSquareOutlined,
  CloseSquareOutlined,
  TrophyOutlined,
  AlertOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';
import { getDashboardStats } from '../../api/host';
import { taskStatusMap, getResourceColor } from '../../utils/status';
import type { DashboardStats, Task, AgentBriefVO } from '../../types';

const GaugeCard: React.FC<{ title: string; value: number; color: string }> = ({ title, value, color }) => {
  const { token } = theme.useToken();
  return (
    <Card size="small" style={{ textAlign: 'center' }}>
      <Progress
        type="dashboard"
        percent={Number((value ?? 0).toFixed(1))}
        size={100}
        strokeColor={color}
        format={(p) => <span style={{ color: token.colorText, fontSize: 16, fontWeight: 600 }}>{p}%</span>}
      />
      <div style={{ marginTop: 8, fontSize: 14, color: token.colorTextSecondary }}>{title}</div>
    </Card>
  );
};

const Dashboard: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [lastUpdated, setLastUpdated] = useState<string>('');

  const fetchStats = (manual = false) => {
    if (manual) setRefreshing(true);
    getDashboardStats()
      .then((res) => {
        if (res.code === 200) {
          setStats(res.data);
          setLastUpdated(dayjs().format('HH:mm:ss'));
        }
      })
      .finally(() => {
        setLoading(false);
        if (manual) setRefreshing(false);
      });
  };

  useEffect(() => {
    fetchStats();
    const timer = setInterval(() => fetchStats(), 10000);
    return () => clearInterval(timer);
  }, []);

  if (loading) {
    return <Spin size="large" style={{ display: 'flex', justifyContent: 'center', marginTop: 100 }} />;
  }

  const recentTaskColumns: ColumnsType<Task> = [
    { title: t('dashboard.taskName'), dataIndex: 'name', key: 'name', ellipsis: true },
    {
      title: t('dashboard.status'), dataIndex: 'status', key: 'status', width: 90,
      render: (status: number) => {
        const s = taskStatusMap[status] || taskStatusMap[0];
        return <Tag color={s.color}>{t(s.text)}</Tag>;
      },
    },
    {
      title: t('dashboard.progress'), key: 'progress', width: 120,
      render: (_, r) => <span><Tag color="green">{r.successCount}</Tag>/{r.totalCount}</span>,
    },
    {
      title: t('dashboard.createdAt'), dataIndex: 'createdAt', key: 'createdAt', width: 160,
      render: (v: string) => v ? dayjs(v).format('MM-DD HH:mm:ss') : '-',
    },
  ];

  const agentColumns: ColumnsType<AgentBriefVO> = [
    { title: t('dashboard.hostname'), dataIndex: 'hostname', key: 'hostname', ellipsis: true },
    { title: t('dashboard.ip'), dataIndex: 'ip', key: 'ip', width: 130 },
    {
      title: t('dashboard.cpu'), dataIndex: 'cpuUsage', key: 'cpuUsage', width: 100,
      render: (v: number) => <Progress percent={Number((v ?? 0).toFixed(1))} size="small"
        strokeColor={getResourceColor(v ?? 0)} />,
    },
    {
      title: t('dashboard.memory'), dataIndex: 'memUsage', key: 'memUsage', width: 100,
      render: (v: number) => <Progress percent={Number((v ?? 0).toFixed(1))} size="small"
        strokeColor={getResourceColor(v ?? 0)} />,
    },
    {
      title: t('dashboard.disk'), dataIndex: 'diskUsage', key: 'diskUsage', width: 100,
      render: (v: number) => <Progress percent={Number((v ?? 0).toFixed(1))} size="small"
        strokeColor={getResourceColor(v ?? 0)} />,
    },
    {
      title: t('dashboard.heartbeat'), dataIndex: 'lastHeartbeat', key: 'lastHeartbeat', width: 130,
      render: (v: string) => v ? dayjs(v).format('HH:mm:ss') : '-',
    },
  ];

  const statCards = [
    { title: t('dashboard.totalHosts'), value: stats?.totalAgents ?? 0, icon: <DesktopOutlined />, color: token.colorPrimary },
    { title: t('dashboard.onlineHosts'), value: stats?.onlineAgents ?? 0, icon: <CheckCircleOutlined />, color: '#52c41a' },
    { title: t('dashboard.offlineHosts'), value: stats?.offlineAgents ?? 0, icon: <CloseCircleOutlined />, color: '#ff4d4f' },
    { title: t('dashboard.unstable'), value: stats?.unstableAgents ?? 0, icon: <WarningOutlined />, color: '#faad14' },
    { title: t('dashboard.totalScripts'), value: stats?.totalScripts ?? 0, icon: <CodeOutlined />, color: '#8b5cf6' },
    { title: t('dashboard.totalTasks'), value: stats?.totalTasks ?? 0, icon: <PlayCircleOutlined />, color: '#fa8c16' },
  ];

  const todayCards = [
    { title: t('dashboard.todayTasks'), value: stats?.todayTasks ?? 0, icon: <CalendarOutlined />, color: token.colorPrimary },
    { title: t('dashboard.todaySuccess'), value: stats?.todaySuccessTasks ?? 0, icon: <CheckSquareOutlined />, color: '#52c41a' },
    { title: t('dashboard.todayFailed'), value: stats?.todayFailedTasks ?? 0, icon: <CloseSquareOutlined />, color: '#ff4d4f' },
  ];

  const alertCount = (stats?.highCpuAgents ?? 0) + (stats?.highMemAgents ?? 0) + (stats?.highDiskAgents ?? 0);

  return (
    <div>
      {/* Host Stats */}
      <Row gutter={[16, 16]}>
        {statCards.map((s) => (
          <Col xs={12} sm={8} lg={4} key={s.title}>
            <Card
              data-testid={`stat-card-${s.title}`}
              style={{ borderRadius: 12 }}
              styles={{ body: { padding: '20px 24px' } }}
            >
              <Statistic
                title={<span style={{ color: token.colorTextSecondary, fontSize: 13 }}>{s.title}</span>}
                value={s.value}
                prefix={<span style={{ color: s.color, fontSize: 22, marginRight: 4 }}>{s.icon}</span>}
                valueStyle={{ color: s.color, fontSize: 28, fontWeight: 700 }}
              />
            </Card>
          </Col>
        ))}
      </Row>

      {/* Today Stats + Success Rate */}
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        {todayCards.map((s) => (
          <Col xs={12} sm={8} lg={6} key={s.title}>
            <Card size="small" style={{ borderRadius: 12 }} styles={{ body: { padding: '16px 20px' } }}>
              <Statistic
                title={<span style={{ color: token.colorTextSecondary, fontSize: 12 }}>{s.title}</span>}
                value={s.value}
                prefix={<span style={{ color: s.color, fontSize: 18, marginRight: 4 }}>{s.icon}</span>}
                valueStyle={{ color: s.color, fontSize: 22, fontWeight: 700 }}
              />
            </Card>
          </Col>
        ))}
        <Col xs={12} sm={8} lg={6}>
          <Card size="small" style={{ borderRadius: 12 }} styles={{ body: { padding: '16px 20px' } }}>
            <Statistic
              title={<span style={{ color: token.colorTextSecondary, fontSize: 12 }}>{t('dashboard.taskSuccessRate')}</span>}
              value={stats?.taskSuccessRate != null ? stats.taskSuccessRate : undefined}
              formatter={stats?.taskSuccessRate != null ? undefined : () => <span style={{ color: token.colorTextTertiary }}>-</span>}
              suffix={stats?.taskSuccessRate != null ? '%' : undefined}
              precision={1}
              prefix={<span style={{ color: '#8b5cf6', fontSize: 18, marginRight: 4 }}><TrophyOutlined /></span>}
              valueStyle={{ color: '#8b5cf6', fontSize: 22, fontWeight: 700 }}
            />
          </Card>
        </Col>
      </Row>

      {/* Resource Alerts Banner */}
      {alertCount > 0 && (
        <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
          <Col span={24}>
            <Card size="small" style={{ borderRadius: 12, borderLeft: '4px solid #faad14' }}
              styles={{ body: { padding: '12px 20px', display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' } }}>
              <AlertOutlined style={{ color: '#faad14', fontSize: 20 }} />
              <Typography.Text strong style={{ color: '#faad14' }}>{t('dashboard.resourceAlert')}</Typography.Text>
              {(stats?.highCpuAgents ?? 0) > 0 && (
                <Tooltip title={t('dashboard.cpuOver80')}>
                  <Tag color="red">{t('dashboard.highCpuCount', { count: stats?.highCpuAgents })}</Tag>
                </Tooltip>
              )}
              {(stats?.highMemAgents ?? 0) > 0 && (
                <Tooltip title={t('dashboard.memOver80')}>
                  <Tag color="orange">{t('dashboard.highMemCount', { count: stats?.highMemAgents })}</Tag>
                </Tooltip>
              )}
              {(stats?.highDiskAgents ?? 0) > 0 && (
                <Tooltip title={t('dashboard.diskOver90')}>
                  <Tag color="volcano">{t('dashboard.highDiskCount', { count: stats?.highDiskAgents })}</Tag>
                </Tooltip>
              )}
            </Card>
          </Col>
        </Row>
      )}

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={8}>
          <Card size="small" data-testid="resource-usage-card" title={<span style={{ color: token.colorText }}><DashboardOutlined style={{ marginRight: 8 }} />{t('dashboard.avgResourceUsage')}</span>}
            extra={<Typography.Text style={{ fontSize: 11, color: token.colorTextTertiary }}>{lastUpdated ? t('dashboard.updated', { time: lastUpdated }) : ''} <ReloadOutlined spin={refreshing} onClick={() => fetchStats(true)} style={{ cursor: 'pointer', marginLeft: 4 }} /></Typography.Text>}
            style={{ borderRadius: 12, height: '100%' }}>
            <Row gutter={8}>
              <Col span={8}>
                <GaugeCard title={t('dashboard.cpu')} value={stats?.avgCpuUsage ?? 0} color={token.colorPrimary} />
              </Col>
              <Col span={8}>
                <GaugeCard title={t('dashboard.memory')} value={stats?.avgMemUsage ?? 0} color="#52c41a" />
              </Col>
              <Col span={8}>
                <GaugeCard title={t('dashboard.disk')} value={stats?.avgDiskUsage ?? 0} color="#fa8c16" />
              </Col>
            </Row>
          </Card>
        </Col>
        <Col xs={24} lg={16}>
          <Card size="small" data-testid="online-agents-card" title={<span style={{ color: token.colorText }}>{t('dashboard.onlineHostDetails')}</span>}
            style={{ borderRadius: 12 }}>
            <Table<AgentBriefVO>
              columns={agentColumns}
              dataSource={stats?.onlineAgentDetails || []}
              rowKey="id"
              size="small"
              pagination={false}
              scroll={{ x: 600 }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col span={24}>
          <Card size="small" data-testid="recent-tasks-card" title={<span style={{ color: token.colorText }}>{t('dashboard.recentTasks')}</span>}
            style={{ borderRadius: 12 }}
            extra={<Typography.Text style={{ fontSize: 12, color: token.colorTextTertiary }}>{t('dashboard.last10')}</Typography.Text>}>
            <Table<Task>
              columns={recentTaskColumns}
              dataSource={stats?.recentTasks || []}
              rowKey="id"
              size="small"
              pagination={false}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default Dashboard;
