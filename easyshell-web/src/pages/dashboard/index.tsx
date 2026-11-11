import { useEffect, useState } from 'react';
import { Row, Col, Card, Spin, Table, Tag, Progress, Typography, theme, Tooltip } from 'antd';
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
    <div style={{ textAlign: 'center', padding: '4px 0' }}>
      <Progress
        type="dashboard"
        percent={Number((value ?? 0).toFixed(1))}
        size={88}
        strokeColor={color}
        trailColor={token.colorFillSecondary}
        format={(p) => <span style={{ color: token.colorText, fontSize: 15, fontWeight: 600 }}>{p}%</span>}
      />
      <div style={{ marginTop: 4, fontSize: 12, color: token.colorTextSecondary }}>{title}</div>
    </div>
  );
};

const StatTile: React.FC<{
  title: string;
  value: number | string;
  icon: React.ReactNode;
  accent: string;
  suffix?: string;
  testId?: string;
}> = ({ title, value, icon, accent, suffix, testId }) => {
  const { token } = theme.useToken();
  return (
    <Card
      size="small"
      data-testid={testId}
      styles={{ body: { padding: '14px 16px' } }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <span style={{
          width: 32,
          height: 32,
          borderRadius: 8,
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexShrink: 0,
          background: token.colorFillTertiary,
          color: accent,
          fontSize: 15,
        }}>
          {icon}
        </span>
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 12, lineHeight: '16px', color: token.colorTextSecondary, marginBottom: 2 }}>{title}</div>
          <div style={{ fontSize: 22, lineHeight: '26px', fontWeight: 600, color: token.colorText, letterSpacing: '-0.02em' }}>
            {value}{suffix ? <span style={{ fontSize: 13, fontWeight: 500, marginLeft: 2, color: token.colorTextSecondary }}>{suffix}</span> : null}
          </div>
        </div>
      </div>
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

  const accent = token.colorPrimary;
  const ok = token.colorSuccess;
  const bad = token.colorError;
  const warn = token.colorWarning;
  const unavailable = t('dashboard.statisticsUnavailable');
  const taskValue = (value: number | null | undefined) => stats?.tasksAvailable === true && value != null ? value : unavailable;

  const statCards = [
    { title: t('dashboard.totalHosts'), value: stats?.totalAgents ?? 0, icon: <DesktopOutlined />, color: accent },
    { title: t('dashboard.onlineHosts'), value: stats?.onlineAgents ?? 0, icon: <CheckCircleOutlined />, color: ok },
    { title: t('dashboard.offlineHosts'), value: stats?.offlineAgents ?? 0, icon: <CloseCircleOutlined />, color: bad },
    { title: t('dashboard.unstable'), value: stats?.unstableAgents ?? 0, icon: <WarningOutlined />, color: warn },
    { title: t('dashboard.totalScripts'), value: stats?.scriptsAvailable === true && stats.totalScripts != null ? stats.totalScripts : unavailable, icon: <CodeOutlined />, color: accent },
    { title: t('dashboard.totalTasks'), value: taskValue(stats?.totalTasks), icon: <PlayCircleOutlined />, color: accent },
  ];

  const todayCards = [
    { title: t('dashboard.todayTasks'), value: taskValue(stats?.todayTasks), icon: <CalendarOutlined />, color: accent },
    { title: t('dashboard.todaySuccess'), value: taskValue(stats?.todaySuccessTasks), icon: <CheckSquareOutlined />, color: ok },
    { title: t('dashboard.todayFailed'), value: taskValue(stats?.todayFailedTasks), icon: <CloseSquareOutlined />, color: bad },
  ];

  const alertCount = (stats?.highCpuAgents ?? 0) + (stats?.highMemAgents ?? 0) + (stats?.highDiskAgents ?? 0);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <Row gutter={[12, 12]}>
        {statCards.map((s) => (
          <Col xs={12} sm={8} lg={4} key={s.title}>
            <StatTile
              testId={`stat-card-${s.title}`}
              title={s.title}
              value={s.value}
              icon={s.icon}
              accent={s.color}
            />
          </Col>
        ))}
      </Row>

      <Row gutter={[12, 12]}>
        {todayCards.map((s) => (
          <Col xs={12} sm={8} lg={6} key={s.title}>
            <StatTile title={s.title} value={s.value} icon={s.icon} accent={s.color} />
          </Col>
        ))}
        <Col xs={12} sm={8} lg={6}>
          <StatTile
            title={t('dashboard.taskSuccessRate')}
            value={stats?.tasksAvailable === true && stats.taskSuccessRate != null ? Number(stats.taskSuccessRate).toFixed(1) : unavailable}
            suffix={stats?.tasksAvailable === true && stats.taskSuccessRate != null ? '%' : undefined}
            icon={<TrophyOutlined />}
            accent={accent}
          />
        </Col>
      </Row>

      {alertCount > 0 && (
        <Card
          size="small"
          style={{ borderLeft: `3px solid ${token.colorWarning}` }}
          styles={{ body: { padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' } }}
        >
          <AlertOutlined style={{ color: token.colorWarning, fontSize: 16 }} />
          <Typography.Text strong style={{ color: token.colorText }}>{t('dashboard.resourceAlert')}</Typography.Text>
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
      )}

      <Row gutter={[12, 12]}>
        <Col xs={24} lg={8}>
          <Card
            size="small"
            data-testid="resource-usage-card"
            title={<span style={{ color: token.colorText }}><DashboardOutlined style={{ marginRight: 8 }} />{t('dashboard.avgResourceUsage')}</span>}
            extra={<Typography.Text style={{ fontSize: 12, color: token.colorTextSecondary }}>{lastUpdated ? t('dashboard.updated', { time: lastUpdated }) : ''} <ReloadOutlined spin={refreshing} onClick={() => fetchStats(true)} style={{ cursor: 'pointer', marginLeft: 4, color: token.colorTextSecondary }} /></Typography.Text>}
            style={{ height: '100%' }}
          >
            <Row gutter={8}>
              <Col span={8}>
                <GaugeCard title={t('dashboard.cpu')} value={stats?.avgCpuUsage ?? 0} color={token.colorPrimary} />
              </Col>
              <Col span={8}>
                <GaugeCard title={t('dashboard.memory')} value={stats?.avgMemUsage ?? 0} color={token.colorSuccess} />
              </Col>
              <Col span={8}>
                <GaugeCard title={t('dashboard.disk')} value={stats?.avgDiskUsage ?? 0} color={token.colorWarning} />
              </Col>
            </Row>
          </Card>
        </Col>
        <Col xs={24} lg={16}>
          <Card
            size="small"
            data-testid="online-agents-card"
            title={<span style={{ color: token.colorText }}>{t('dashboard.onlineHostDetails')}</span>}
          >
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

      <Card
        size="small"
        data-testid="recent-tasks-card"
        title={<span style={{ color: token.colorText }}>{t('dashboard.recentTasks')}</span>}
        extra={stats?.tasksAvailable === true && stats.recentTasks != null ? <Typography.Text style={{ fontSize: 12, color: token.colorTextSecondary }}>{t('dashboard.last10')}</Typography.Text> : null}
      >
        {stats?.tasksAvailable === true && stats.recentTasks != null ? <Table<Task>
          columns={recentTaskColumns}
          dataSource={stats.recentTasks}
          rowKey="id"
          size="small"
          pagination={false}
        /> : <Typography.Text>{unavailable}</Typography.Text>}
      </Card>
    </div>
  );
};

export default Dashboard;
