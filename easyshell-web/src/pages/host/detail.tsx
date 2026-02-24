import { useEffect, useState, useCallback, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Card, Descriptions, Tag, Progress, Row, Col, Table, Button, Space, Spin, Typography, theme, Radio, Empty, message, Tabs,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ArrowLeftOutlined, DesktopOutlined, InfoCircleOutlined,
  HddOutlined, HistoryOutlined, CodeOutlined, ReloadOutlined,
  LineChartOutlined, SearchOutlined, CloudServerOutlined,
} from '@ant-design/icons';
import { Line } from '@ant-design/charts';
import dayjs from 'dayjs';
import {
  getHostDetail, getAgentTags, getAgentJobs, getAgentMetrics,
  triggerDetection, parseDetection, getHostSoftware, getDockerContainers,
} from '../../api/host';
import type { Agent, TagVO, Job, MetricSnapshot, HostSoftwareInventory } from '../../types';

const statusMap: Record<number, { color: string; text: string }> = {
  0: { color: 'red', text: 'offline' },
  1: { color: 'green', text: 'online' },
  2: { color: 'orange', text: 'unstable' },
};

const jobStatusMap: Record<number, { color: string; text: string }> = {
  0: { color: 'default', text: 'pending' },
  1: { color: 'processing', text: 'running' },
  2: { color: 'success', text: 'success' },
  3: { color: 'warning', text: 'partialFailed' },
  4: { color: 'error', text: 'failed' },
  5: { color: 'orange', text: 'timeout' },
};

const softwareTypeMap: Record<string, { color: string; text: string }> = {
  database: { color: 'blue', text: 'database' },
  service: { color: 'green', text: 'service' },
  runtime: { color: 'purple', text: 'runtime' },
  container_engine: { color: 'cyan', text: 'containerEngine' },
  container: { color: 'orange', text: 'container' },
  other: { color: 'default', text: 'other' },
};

const formatBytes = (bytes: number) => {
  if (!bytes) return '-';
  const gb = bytes / (1024 * 1024 * 1024);
  return `${gb.toFixed(2)} GB`;
};

const ResourceGauge: React.FC<{ title: string; value: number; color: string }> = ({ title, value, color }) => {
  const { token } = theme.useToken();
  return (
    <div style={{ textAlign: 'center' }}>
      <Progress
        type="dashboard"
        percent={Number((value ?? 0).toFixed(1))}
        size={120}
        strokeColor={value > 80 ? '#ff4d4f' : value > 60 ? '#faad14' : color}
        format={(p) => <span style={{ fontSize: 20, fontWeight: 700, color: token.colorText }}>{p}%</span>}
      />
      <div style={{ marginTop: 8, fontSize: 14, color: token.colorTextSecondary, fontWeight: 500 }}>{title}</div>
    </div>
  );
};

const timeRangeOptions = [
  { label: '1h', value: '1h' },
  { label: '6h', value: '6h' },
  { label: '24h', value: '24h' },
  { label: '7d', value: '7d' },
  { label: '30d', value: '30d' },
];

const HostDetail: React.FC = () => {
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const { agentId } = useParams<{ agentId: string }>();
  const navigate = useNavigate();
  const [agent, setAgent] = useState<Agent | null>(null);
  const [tags, setTags] = useState<TagVO[]>([]);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [metricsRange, setMetricsRange] = useState('1h');
  const [metrics, setMetrics] = useState<MetricSnapshot[]>([]);
  const [metricsLoading, setMetricsLoading] = useState(false);

  // Software inventory state
  const [software, setSoftware] = useState<HostSoftwareInventory[]>([]);
  const [containers, setContainers] = useState<HostSoftwareInventory[]>([]);
  const [softwareLoading, setSoftwareLoading] = useState(false);
  const [detecting, setDetecting] = useState(false);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchData = useCallback((manual = false) => {
    if (!agentId) return;
    if (manual) setRefreshing(true);
    else setLoading(true);
    Promise.all([
      getHostDetail(agentId),
      getAgentTags(agentId),
      getAgentJobs(agentId),
    ])
      .then(([agentRes, tagsRes, jobsRes]) => {
        if (agentRes.code === 200) setAgent(agentRes.data);
        if (tagsRes.code === 200) setTags(tagsRes.data || []);
        if (jobsRes.code === 200) setJobs(jobsRes.data || []);
      })
      .finally(() => {
        setLoading(false);
        if (manual) setRefreshing(false);
      });
  }, [agentId]);

  const fetchMetrics = useCallback((range: string) => {
    if (!agentId) return;
    setMetricsLoading(true);
    getAgentMetrics(agentId, range)
      .then((res) => {
        if (res.code === 200) setMetrics(res.data || []);
      })
      .finally(() => setMetricsLoading(false));
  }, [agentId]);

  const fetchSoftwareInventory = useCallback(() => {
    if (!agentId) return;
    setSoftwareLoading(true);
    Promise.all([
      getHostSoftware(agentId),
      getDockerContainers(agentId),
    ])
      .then(([swRes, dockerRes]) => {
        if (swRes.code === 200) setSoftware(swRes.data || []);
        if (dockerRes.code === 200) setContainers(dockerRes.data || []);
      })
      .finally(() => setSoftwareLoading(false));
  }, [agentId]);

  const handleDetect = useCallback(async () => {
    if (!agentId) return;
    setDetecting(true);
    try {
      const res = await triggerDetection(agentId);
      if (res.code !== 200) {
        message.error(res.message || t('hostDetail.detectFailed'));
        setDetecting(false);
        return;
      }
      const taskId = res.data;
      message.info(t('hostDetail.detectTaskSubmitted'));

      let attempts = 0;
      const maxAttempts = 30;
      if (pollTimerRef.current) clearInterval(pollTimerRef.current);

      pollTimerRef.current = setInterval(async () => {
        attempts++;
        if (attempts >= maxAttempts) {
          if (pollTimerRef.current) clearInterval(pollTimerRef.current);
          setDetecting(false);
          message.warning(t('hostDetail.detectTimeout'));
          return;
        }
        try {
          const parseRes = await parseDetection(agentId, taskId);
          if (parseRes.code === 200) {
            if (pollTimerRef.current) clearInterval(pollTimerRef.current);
            setDetecting(false);
            message.success(t('hostDetail.detectComplete', { count: parseRes.data.length }));
            fetchSoftwareInventory();
          }
        } catch {
          // Task not done yet
        }
      }, 2000);
    } catch {
      message.error(t('hostDetail.detectRequestFailed'));
      setDetecting(false);
    }
  }, [agentId, fetchSoftwareInventory, t]);

  useEffect(() => {
    fetchData();
    const timer = setInterval(() => fetchData(), 10000);
    return () => clearInterval(timer);
  }, [fetchData]);

  useEffect(() => {
    fetchMetrics(metricsRange);
  }, [fetchMetrics, metricsRange]);

  useEffect(() => {
    fetchSoftwareInventory();
  }, [fetchSoftwareInventory]);

  useEffect(() => {
    return () => {
      if (pollTimerRef.current) clearInterval(pollTimerRef.current);
    };
  }, []);

  if (loading && !agent) {
    return <Spin size="large" style={{ display: 'flex', justifyContent: 'center', marginTop: 100 }} />;
  }

  if (!agent) {
    return <div style={{ textAlign: 'center', padding: 60, color: token.colorTextTertiary }}>{t('hostDetail.notFound')}</div>;
  }

  const s = statusMap[agent.status] || statusMap[0];

  const jobColumns: ColumnsType<Job> = [
    { title: t('hostDetail.taskId'), dataIndex: 'taskId', key: 'taskId', width: 200, ellipsis: true },
    {
      title: t('host.status'), dataIndex: 'status', key: 'status', width: 100,
      render: (status: number) => {
        const js = jobStatusMap[status] || jobStatusMap[0];
        return <Tag color={js.color}>{t(`hostDetail.jobStatus.${js.text}`)}</Tag>;
      },
    },
    {
      title: t('hostDetail.exitCode'), dataIndex: 'exitCode', key: 'exitCode', width: 80,
      render: (val: number) => val !== null && val !== undefined ? (
        <Tag color={val === 0 ? 'green' : 'red'}>{val}</Tag>
      ) : '-',
    },
    {
      title: t('hostDetail.output'), dataIndex: 'output', key: 'output', ellipsis: true,
      render: (val: string) => val || '-',
    },
    {
      title: t('hostDetail.startTime'), dataIndex: 'startedAt', key: 'startedAt', width: 170,
      render: (val: string) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
    {
      title: t('hostDetail.finishTime'), dataIndex: 'finishedAt', key: 'finishedAt', width: 170,
      render: (val: string) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
  ];

  const softwareColumns: ColumnsType<HostSoftwareInventory> = [
    {
      title: t('hostDetail.softwareName'), dataIndex: 'softwareName', key: 'softwareName', width: 140,
      render: (val: string) => <Typography.Text strong>{val}</Typography.Text>,
    },
    {
      title: t('hostDetail.version'), dataIndex: 'softwareVersion', key: 'softwareVersion', width: 160,
      render: (val: string | null) => val || '-',
    },
    {
      title: t('hostDetail.softwareType'), dataIndex: 'softwareType', key: 'softwareType', width: 120,
      render: (val: string) => {
        const st = softwareTypeMap[val] || softwareTypeMap.other;
        return <Tag color={st.color}>{t(`hostDetail.softwareTypes.${st.text}`)}</Tag>;
      },
    },
    {
      title: 'PID', dataIndex: 'processId', key: 'processId', width: 80,
      render: (val: number | null) => val || '-',
    },
    {
      title: t('hostDetail.listeningPorts'), dataIndex: 'listeningPorts', key: 'listeningPorts', width: 140,
      render: (val: string | null) => val ? (
        <Space size={4} wrap>
          {val.split(',').map((p) => <Tag key={p} color="blue">{p}</Tag>)}
        </Space>
      ) : '-',
    },
    {
      title: t('hostDetail.detectTime'), dataIndex: 'detectedAt', key: 'detectedAt', width: 170,
      render: (val: string) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
  ];

  const containerColumns: ColumnsType<HostSoftwareInventory> = [
    {
      title: t('hostDetail.containerName'), dataIndex: 'dockerContainerName', key: 'dockerContainerName', width: 180,
      render: (val: string | null) => <Typography.Text strong>{val || '-'}</Typography.Text>,
    },
    {
      title: t('hostDetail.image'), dataIndex: 'dockerImage', key: 'dockerImage', width: 240,
      render: (val: string | null) => <Typography.Text code>{val || '-'}</Typography.Text>,
    },
    {
      title: t('hostDetail.portMapping'), dataIndex: 'dockerPorts', key: 'dockerPorts', ellipsis: true,
      render: (val: string | null) => val || '-',
    },
    {
      title: t('host.status'), dataIndex: 'dockerContainerStatus', key: 'dockerContainerStatus', width: 160,
      render: (val: string | null) => {
        if (!val) return '-';
        const isUp = val.toLowerCase().startsWith('up');
        return <Tag color={isUp ? 'green' : 'red'}>{val}</Tag>;
      },
    },
    {
      title: t('hostDetail.detectTime'), dataIndex: 'detectedAt', key: 'detectedAt', width: 170,
      render: (val: string) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/host')}>{t('common.back')}</Button>
        <Button type="primary" icon={<CodeOutlined />} onClick={() => navigate(`/terminal/${agentId}`)} disabled={agent.status !== 1}>
          {t('hostDetail.webTerminal')}
        </Button>
        <Typography.Title level={4} style={{ margin: 0 }}>
          <DesktopOutlined style={{ marginRight: 8 }} />
          {agent.hostname}
          <Tag color={s.color} style={{ marginLeft: 12 }}>{t(`host.${s.text}`)}</Tag>
        </Typography.Title>
      </Space>

      {/* Resource Usage */}
      <Card
        size="small"
        title={<span style={{ color: token.colorText }}><HddOutlined style={{ marginRight: 8, color: token.colorPrimary }} />{t('hostDetail.resourceUsage')}</span>}
        extra={<ReloadOutlined spin={refreshing} onClick={() => fetchData(true)} style={{ cursor: 'pointer', color: token.colorTextTertiary }} />}
        style={{ marginBottom: 16, borderRadius: 12 }}
      >
        <Row gutter={24} justify="center">
          <Col><ResourceGauge title={t('hostDetail.cpuUsage')} value={agent.cpuUsage} color="#1890ff" /></Col>
          <Col><ResourceGauge title={t('hostDetail.memoryUsage')} value={agent.memUsage} color="#52c41a" /></Col>
          <Col><ResourceGauge title={t('hostDetail.diskUsage')} value={agent.diskUsage} color="#fa8c16" /></Col>
        </Row>
      </Card>

      {/* Resource History Charts */}
      <Card
        size="small"
        title={<span style={{ color: token.colorText }}><LineChartOutlined style={{ marginRight: 8, color: token.colorPrimary }} />{t('hostDetail.resourceHistory')}</span>}
        extra={
          <Radio.Group
            value={metricsRange}
            onChange={(e) => setMetricsRange(e.target.value)}
            size="small"
            optionType="button"
            buttonStyle="solid"
            options={timeRangeOptions}
          />
        }
        style={{ marginBottom: 16, borderRadius: 12 }}
      >
        <Spin spinning={metricsLoading}>
          {metrics.length === 0 ? (
            <Empty description={t('hostDetail.noHistoryData')} style={{ padding: '40px 0' }} />
          ) : (
            <Line
              data={metrics.flatMap((m) => [
                { time: m.recordedAt, value: m.cpuUsage ?? 0, metric: 'CPU' },
                { time: m.recordedAt, value: m.memUsage ?? 0, metric: t('host.memory') },
                { time: m.recordedAt, value: m.diskUsage ?? 0, metric: t('host.disk') },
              ])}
              xField={(d: { time: string }) => new Date(d.time)}
              yField="value"
              colorField="metric"
              smooth
              height={320}
              scale={{
                y: { nice: true, min: 0, max: 100 },
                color: { range: ['#1890ff', '#52c41a', '#fa8c16'] },
              }}
              axis={{
                x: { title: false },
                y: { title: t('hostDetail.usagePercent') },
              }}
              tooltip={{
                title: (d: { time: string }) => dayjs(d.time).format('YYYY-MM-DD HH:mm:ss'),
              }}
              style={{ lineWidth: 2 }}
              legend={{ position: 'top' }}
            />
          )}
        </Spin>
      </Card>

      {/* System Info */}
      <Card
        size="small"
        title={<span style={{ color: token.colorText }}><InfoCircleOutlined style={{ marginRight: 8, color: token.colorPrimary }} />{t('hostDetail.systemInfo')}</span>}
        style={{ marginBottom: 16, borderRadius: 12 }}
      >
        <Descriptions bordered size="small" column={{ xxl: 3, xl: 3, lg: 2, md: 2, sm: 1, xs: 1 }}>
          <Descriptions.Item label={t('host.hostname')}>{agent.hostname}</Descriptions.Item>
          <Descriptions.Item label={t('host.ipAddress')}>{agent.ip}</Descriptions.Item>
          <Descriptions.Item label={t('host.os')}>{agent.os}</Descriptions.Item>
          <Descriptions.Item label={t('host.arch')}>{agent.arch}</Descriptions.Item>
          <Descriptions.Item label={t('hostDetail.kernel')}>{agent.kernel || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('hostDetail.cpuModel')}>{agent.cpuModel || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('hostDetail.cpuCores')}>{agent.cpuCores || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('host.totalMemory')}>{formatBytes(agent.memTotal)}</Descriptions.Item>
          <Descriptions.Item label={t('host.agentVersion')}>{agent.agentVersion || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('hostDetail.registeredAt')}>
            {agent.registeredAt ? dayjs(agent.registeredAt).format('YYYY-MM-DD HH:mm:ss') : '-'}
          </Descriptions.Item>
          <Descriptions.Item label={t('host.lastHeartbeat')}>
            {agent.lastHeartbeat ? dayjs(agent.lastHeartbeat).format('YYYY-MM-DD HH:mm:ss') : '-'}
          </Descriptions.Item>
          <Descriptions.Item label={t('host.tags')}>
            {tags.length > 0 ? tags.map((t) => (
              <Tag key={t.id} color={t.color || 'blue'}>{t.name}</Tag>
            )) : <span style={{ color: token.colorTextTertiary }}>{t('hostDetail.noTags')}</span>}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      {/* Software Inventory */}
      <Card
        size="small"
        title={<span style={{ color: token.colorText }}><CloudServerOutlined style={{ marginRight: 8, color: token.colorPrimary }} />{t('hostDetail.softwareInventory')}</span>}
        extra={
          <Space>
            <Button
              icon={<SearchOutlined />}
              type="primary"
              size="small"
              loading={detecting}
              onClick={handleDetect}
              disabled={agent.status !== 1}
            >
              {detecting ? t('hostDetail.detecting') : t('hostDetail.startDetect')}
            </Button>
            <ReloadOutlined
              onClick={fetchSoftwareInventory}
              style={{ cursor: 'pointer', color: token.colorTextTertiary }}
            />
          </Space>
        }
        style={{ marginBottom: 16, borderRadius: 12 }}
      >
        <Spin spinning={softwareLoading}>
          <Tabs
            defaultActiveKey="software"
            items={[
              {
                key: 'software',
                label: `${t('hostDetail.runningSoftware')} (${software.length})`,
                children: software.length === 0 ? (
                  <Empty description={t('hostDetail.noSoftwareData')} style={{ padding: '40px 0' }} />
                ) : (
                  <Table<HostSoftwareInventory>
                    columns={softwareColumns}
                    dataSource={software}
                    rowKey="id"
                    size="small"
                    pagination={false}
                    scroll={{ x: 800 }}
                  />
                ),
              },
              {
                key: 'docker',
                label: `${t('hostDetail.dockerContainers')} (${containers.length})`,
                children: containers.length === 0 ? (
                  <Empty description={t('hostDetail.noContainers')} style={{ padding: '40px 0' }} />
                ) : (
                  <Table<HostSoftwareInventory>
                    columns={containerColumns}
                    dataSource={containers}
                    rowKey="id"
                    size="small"
                    pagination={false}
                    scroll={{ x: 900 }}
                  />
                ),
              },
            ]}
          />
        </Spin>
      </Card>

      {/* Operation History */}
      <Card
        size="small"
        title={<span style={{ color: token.colorText }}><HistoryOutlined style={{ marginRight: 8, color: token.colorPrimary }} />{t('hostDetail.executionHistory')}</span>}
        style={{ borderRadius: 12 }}
      >
        <Table<Job>
          columns={jobColumns}
          dataSource={jobs}
          rowKey="id"
          size="small"
          scroll={{ x: 1000 }}
          pagination={{ pageSize: 10 }}
        />
      </Card>
    </div>
  );
};

export default HostDetail;
