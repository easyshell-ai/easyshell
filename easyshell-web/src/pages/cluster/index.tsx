import { useEffect, useState, useCallback } from 'react';
import {
  Card, Table, Button, Modal, Form, Input, Popconfirm, Tag, Space, message,
  Drawer, Transfer, Descriptions, Empty, Typography, theme,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, ClusterOutlined,
  TeamOutlined, DesktopOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';
import {
  getClusterList, createCluster, updateCluster, deleteCluster,
  getClusterDetail, addClusterAgents, removeClusterAgent,
} from '../../api/cluster';
import { getHostList } from '../../api/host';
import type { ClusterVO, ClusterDetailVO, ClusterRequest, Agent } from '../../types';

const { TextArea } = Input;

const ClusterPage: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const [clusters, setClusters] = useState<ClusterVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingCluster, setEditingCluster] = useState<ClusterVO | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<ClusterRequest>();

  // Drawer for detail/agent management
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [clusterDetail, setClusterDetail] = useState<ClusterDetailVO | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  // Agent assignment
  const [allAgents, setAllAgents] = useState<Agent[]>([]);
  const [assignModalOpen, setAssignModalOpen] = useState(false);
  const [targetKeys, setTargetKeys] = useState<string[]>([]);

  const fetchClusters = useCallback(() => {
    setLoading(true);
    getClusterList()
      .then((res) => {
        if (res.code === 200) setClusters(res.data || []);
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchClusters();
  }, [fetchClusters]);

  const handleCreate = () => {
    setEditingCluster(null);
    form.resetFields();
    setModalOpen(true);
  };

  const handleEdit = (record: ClusterVO) => {
    setEditingCluster(record);
    form.setFieldsValue({ name: record.name, description: record.description });
    setModalOpen(true);
  };

  const handleDelete = (id: number) => {
    deleteCluster(id).then((res) => {
      if (res.code === 200) {
        message.success(t('common.deleteSuccess'));
        fetchClusters();
      } else {
        message.error(res.message || t('common.deleteFailed'));
      }
    });
  };

  const handleSubmit = () => {
    form.validateFields().then((values) => {
      setSubmitting(true);
      const action = editingCluster
        ? updateCluster(editingCluster.id, values)
        : createCluster(values);

      action
        .then((res) => {
          if (res.code === 200) {
            message.success(editingCluster ? t('common.updateSuccess') : t('common.createSuccess'));
            setModalOpen(false);
            fetchClusters();
          } else {
            message.error(res.message || t('common.operationFailed'));
          }
        })
        .finally(() => setSubmitting(false));
    });
  };

  const handleViewDetail = (id: number) => {
    setDrawerOpen(true);
    setDetailLoading(true);
    getClusterDetail(id)
      .then((res) => {
        if (res.code === 200) setClusterDetail(res.data);
      })
      .finally(() => setDetailLoading(false));
  };

  const handleOpenAssign = () => {
    getHostList().then((res) => {
      if (res.code === 200) setAllAgents(res.data || []);
    });
    setTargetKeys(clusterDetail?.agents?.map((a) => a.id) || []);
    setAssignModalOpen(true);
  };

  const handleAssignSubmit = () => {
    if (!clusterDetail) return;
    const existingIds = clusterDetail.agents?.map((a) => a.id) || [];
    const toAdd = targetKeys.filter((k) => !existingIds.includes(k));
    const toRemove = existingIds.filter((k) => !targetKeys.includes(k));

    const promises: Promise<unknown>[] = [];
    if (toAdd.length > 0) {
      promises.push(addClusterAgents(clusterDetail.id, toAdd));
    }
    toRemove.forEach((agentId) => {
      promises.push(removeClusterAgent(clusterDetail.id, agentId));
    });

    Promise.all(promises)
      .then(() => {
        message.success(t('cluster.agentAssignUpdated'));
        setAssignModalOpen(false);
        handleViewDetail(clusterDetail.id);
        fetchClusters();
      })
      .catch(() => message.error(t('common.operationFailed')));
  };

  const columns: ColumnsType<ClusterVO> = [
    {
      title: t('cluster.col.name'), dataIndex: 'name', key: 'name', width: 200,
      render: (name: string, record) => (
        <a onClick={() => handleViewDetail(record.id)}>
          <ClusterOutlined style={{ marginRight: 6, color: token.colorPrimary }} />{name}
        </a>
      ),
    },
    { title: t('cluster.col.description'), dataIndex: 'description', key: 'description', ellipsis: true },
    {
      title: t('cluster.col.agentCount'), dataIndex: 'agentCount', key: 'agentCount', width: 100,
      render: (count: number) => (
        <Tag icon={<TeamOutlined />} color="blue">{count}</Tag>
      ),
    },
    {
      title: t('cluster.col.createdAt'), dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (val: string) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
    {
      title: t('cluster.col.actions'), key: 'action', width: 200, fixed: 'right',
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            {t('common.edit')}
          </Button>
          <Button type="link" size="small" icon={<TeamOutlined />} onClick={() => handleViewDetail(record.id)}>
            {t('cluster.manage')}
          </Button>
          <Popconfirm title={t('cluster.confirmDelete')} onConfirm={() => handleDelete(record.id)} okText={t('common.confirm')} cancelText={t('common.cancel')}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>{t('common.delete')}</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const agentColumns: ColumnsType<Agent> = [
    { title: t('cluster.agent.hostname'), dataIndex: 'hostname', key: 'hostname', width: 140 },
    { title: t('cluster.agent.ip'), dataIndex: 'ip', key: 'ip', width: 130 },
    {
      title: t('cluster.agent.status'), dataIndex: 'status', key: 'status', width: 80,
      render: (s: number) => <Tag color={s === 1 ? 'green' : 'red'}>{s === 1 ? t('cluster.agent.online') : t('cluster.agent.offline')}</Tag>,
    },
    { title: t('cluster.agent.os'), dataIndex: 'os', key: 'os', width: 100 },
    {
      title: t('cluster.col.actions'), key: 'action', width: 80,
      render: (_, record) => (
        <Popconfirm
          title={t('cluster.confirmRemoveAgent')}
          onConfirm={() => {
            if (!clusterDetail) return;
            removeClusterAgent(clusterDetail.id, record.id).then(() => {
              message.success(t('cluster.removed'));
              handleViewDetail(clusterDetail.id);
              fetchClusters();
            });
          }}
          okText={t('common.confirm')} cancelText={t('common.cancel')}
        >
          <Button type="link" size="small" danger>{t('cluster.remove')}</Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography.Title level={4} style={{ margin: 0, color: token.colorText }}>
          <ClusterOutlined style={{ marginRight: 8, color: token.colorPrimary }} />{t('cluster.title')}
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>{t('cluster.create')}</Button>
      </div>

      <Card style={{ borderRadius: 12 }} styles={{ body: { padding: 0 } }}>
        <Table<ClusterVO>
          columns={columns}
          dataSource={clusters}
          rowKey="id"
          loading={loading}
          scroll={{ x: 900 }}
          pagination={{ pageSize: 20 }}
        />
      </Card>

      {/* Create/Edit Modal */}
      <Modal
        title={editingCluster ? t('cluster.editTitle') : t('cluster.createTitle')}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        confirmLoading={submitting}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="name" label={t('cluster.field.name')} rules={[{ required: true, message: t('cluster.field.nameRequired') }]}>
            <Input placeholder={t('cluster.field.namePlaceholder')} />
          </Form.Item>
          <Form.Item name="description" label={t('cluster.field.description')}>
            <TextArea rows={3} placeholder={t('cluster.field.descriptionPlaceholder')} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Detail Drawer */}
      <Drawer
        title={<><ClusterOutlined style={{ marginRight: 8 }} />{clusterDetail?.name || t('cluster.detail')}</>}
        placement="right"
        width={700}
        open={drawerOpen}
        onClose={() => { setDrawerOpen(false); setClusterDetail(null); }}
        destroyOnClose
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={handleOpenAssign}>{t('cluster.assignAgents')}</Button>
        }
      >
        {clusterDetail ? (
          <>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('cluster.field.name')}>{clusterDetail.name}</Descriptions.Item>
              <Descriptions.Item label={t('cluster.col.agentCount')}>{clusterDetail.agents?.length || 0}</Descriptions.Item>
              <Descriptions.Item label={t('cluster.field.description')} span={2}>{clusterDetail.description || '-'}</Descriptions.Item>
            </Descriptions>
            <div style={{ marginTop: 24 }}>
              <Typography.Title level={5}><DesktopOutlined style={{ marginRight: 8 }} />{t('cluster.clusterAgents')}</Typography.Title>
              {clusterDetail.agents?.length ? (
                <Table<Agent>
                  columns={agentColumns}
                  dataSource={clusterDetail.agents}
                  rowKey="id"
                  size="small"
                  pagination={false}
                />
              ) : (
                <Empty description={t('cluster.noAgentsAssign')} />
              )}
            </div>
          </>
        ) : detailLoading ? (
          <div style={{ textAlign: 'center', padding: 40, color: token.colorTextTertiary }}>{t('common.loading')}</div>
        ) : null}
      </Drawer>

      {/* Agent Assignment Modal */}
      <Modal
        title={t('cluster.assignAgentsTitle')}
        open={assignModalOpen}
        onOk={handleAssignSubmit}
        onCancel={() => setAssignModalOpen(false)}
        width={700}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
      >
        <Transfer
          dataSource={allAgents.map((a) => ({
            key: a.id,
            title: `${a.hostname} (${a.ip})`,
            description: a.status === 1 ? t('cluster.agent.online') : t('cluster.agent.offline'),
            disabled: false,
          }))}
          targetKeys={targetKeys}
          onChange={(keys) => setTargetKeys(keys as string[])}
          render={(item) => item.title || ''}
          titles={[t('cluster.availableAgents'), t('cluster.assignedAgents')]}
          showSearch
          listStyle={{ width: 280, height: 360 }}
        />
      </Modal>
    </>
  );
};

export default ClusterPage;
