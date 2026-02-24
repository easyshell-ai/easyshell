import { useEffect, useState, useCallback } from 'react';
import { Table, Tag, Select, Space, Typography, Card, theme } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { AuditOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';
import { getAuditLogList } from '../../api/audit';
import type { AuditLog } from '../../types';

const actionColorMap: Record<string, string> = {
  CREATE_CLUSTER: 'green',
  UPDATE_CLUSTER: 'blue',
  DELETE_CLUSTER: 'red',
  ADD_CLUSTER_AGENTS: 'cyan',
  REMOVE_CLUSTER_AGENT: 'orange',
  CREATE_TAG: 'green',
  UPDATE_TAG: 'blue',
  DELETE_TAG: 'red',
  ADD_AGENT_TAG: 'cyan',
  REMOVE_AGENT_TAG: 'orange',
  CREATE_SCRIPT: 'green',
  UPDATE_SCRIPT: 'blue',
  DELETE_SCRIPT: 'red',
  CREATE_TASK: 'green',
};

const actionLabelMap: Record<string, string> = {
  CREATE_CLUSTER: 'audit.action.createCluster',
  UPDATE_CLUSTER: 'audit.action.updateCluster',
  DELETE_CLUSTER: 'audit.action.deleteCluster',
  ADD_CLUSTER_AGENTS: 'audit.action.addClusterAgents',
  REMOVE_CLUSTER_AGENT: 'audit.action.removeClusterAgent',
  CREATE_TAG: 'audit.action.createTag',
  UPDATE_TAG: 'audit.action.updateTag',
  DELETE_TAG: 'audit.action.deleteTag',
  ADD_AGENT_TAG: 'audit.action.addAgentTag',
  REMOVE_AGENT_TAG: 'audit.action.removeAgentTag',
  CREATE_SCRIPT: 'audit.action.createScript',
  UPDATE_SCRIPT: 'audit.action.updateScript',
  DELETE_SCRIPT: 'audit.action.deleteScript',
  CREATE_TASK: 'audit.action.createTask',
};

const resourceTypeLabelMap: Record<string, string> = {
  cluster: 'audit.resource.cluster',
  tag: 'audit.resource.tag',
  script: 'audit.resource.script',
  task: 'audit.resource.task',
};

const AuditPage: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const [data, setData] = useState<AuditLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [filterAction, setFilterAction] = useState<string | undefined>(undefined);
  const [filterResourceType, setFilterResourceType] = useState<string | undefined>(undefined);

  const fetchData = useCallback(() => {
    setLoading(true);
    getAuditLogList({
      action: filterAction,
      resourceType: filterResourceType,
      page,
      size: pageSize,
    })
      .then((res) => {
        if (res.code === 200 && res.data) {
          setData(res.data.content || []);
          setTotal(res.data.totalElements || 0);
        }
      })
      .finally(() => setLoading(false));
  }, [page, pageSize, filterAction, filterResourceType]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const columns: ColumnsType<AuditLog> = [
    {
      title: t('audit.col.username'), dataIndex: 'username', key: 'username', width: 100,
    },
    {
      title: t('audit.col.action'), dataIndex: 'action', key: 'action', width: 140,
      render: (action: string) => (
        <Tag color={actionColorMap[action] || 'default'}>
          {t(actionLabelMap[action]) || action}
        </Tag>
      ),
    },
    {
      title: t('audit.col.resourceType'), dataIndex: 'resourceType', key: 'resourceType', width: 100,
      render: (type: string) => (
        <Tag>{t(resourceTypeLabelMap[type]) || type}</Tag>
      ),
    },
    {
      title: t('audit.col.resourceId'), dataIndex: 'resourceId', key: 'resourceId', width: 120, ellipsis: true,
    },
    {
      title: t('audit.col.detail'), dataIndex: 'detail', key: 'detail', ellipsis: true,
    },
    {
      title: t('audit.col.ip'), dataIndex: 'ip', key: 'ip', width: 130,
    },
    {
      title: t('audit.col.result'), dataIndex: 'result', key: 'result', width: 80,
      render: (result: string) => (
        <Tag color={result === 'success' ? 'green' : 'red'}>
          {result === 'success' ? t('audit.result.success') : t('audit.result.failed')}
        </Tag>
      ),
    },
    {
      title: t('audit.col.time'), dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (val: string) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
  ];

  return (
    <>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography.Title level={4} style={{ margin: 0, color: token.colorText }}>
          <AuditOutlined style={{ marginRight: 8, color: token.colorPrimary }} />{t('audit.title')}
        </Typography.Title>
        <Space>
          <Select
            placeholder={t('audit.filterAction')}
            allowClear
            style={{ width: 160 }}
            value={filterAction}
            onChange={(val) => { setFilterAction(val); setPage(0); }}
            options={Object.entries(actionLabelMap).map(([value, label]) => ({ value, label: t(label) }))}
          />
          <Select
            placeholder={t('audit.filterResourceType')}
            allowClear
            style={{ width: 120 }}
            value={filterResourceType}
            onChange={(val) => { setFilterResourceType(val); setPage(0); }}
            options={Object.entries(resourceTypeLabelMap).map(([value, label]) => ({ value, label: t(label) }))}
          />
        </Space>
      </div>

      <Card style={{ borderRadius: 12 }}>
        <Table<AuditLog>
          columns={columns}
          dataSource={data}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1100 }}
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (total) => t('common.total', { total }),
            onChange: (p, s) => { setPage(p - 1); setPageSize(s); },
          }}
        />
      </Card>
    </>
  );
};

export default AuditPage;
