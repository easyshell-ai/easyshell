import { useState, useEffect, useCallback } from 'react';
import {
  Card,
  Table,
  Button,
  Space,
  Tag,
  Modal,
  Form,
  Input,
  Select,
  message,
  Popconfirm,
  Typography,
  Badge,
  theme,
  Tooltip,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  KeyOutlined,
  UserOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { useResponsive } from '../../hooks/useResponsive';
import {
  getUserList,
  createUser,
  updateUser,
  deleteUser,
  resetUserPassword,
} from '../../api/system';
import type { UserVO } from '../../types';

const { Title } = Typography;

const roleColorMap: Record<string, string> = {
  super_admin: 'red',
  admin: 'blue',
  operator: 'green',
  viewer: 'default',
};

const Users: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const { isMobile } = useResponsive();
  const [users, setUsers] = useState<UserVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [passwordModalOpen, setPasswordModalOpen] = useState(false);
  const [currentUser, setCurrentUser] = useState<UserVO | null>(null);
  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [passwordForm] = Form.useForm();

  const fetchUsers = useCallback(() => {
    setLoading(true);
    getUserList()
      .then((res) => {
        if (res.code === 200) {
          const list = res.data || [];
          setUsers(list);
          setTotal(list.length);
        }
      })
      .catch(() => message.error(t('users.fetchError')))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  const handleCreate = async (values: { username: string; password: string; email?: string; role: string }) => {
    try {
      const res = await createUser(values);
      if (res.code === 200) {
        message.success(t('users.createSuccess'));
        setCreateModalOpen(false);
        createForm.resetFields();
        fetchUsers();
      } else {
        message.error(res.message || t('users.createError'));
      }
    } catch {
      message.error(t('users.createError'));
    }
  };

  const handleEdit = async (values: { email?: string; role?: string; status?: number }) => {
    if (!currentUser) return;
    try {
      const res = await updateUser(currentUser.id, values);
      if (res.code === 200) {
        message.success(t('users.updateSuccess'));
        setEditModalOpen(false);
        editForm.resetFields();
        setCurrentUser(null);
        fetchUsers();
      } else {
        message.error(res.message || t('users.updateError'));
      }
    } catch {
      message.error(t('users.updateError'));
    }
  };

  const handleDelete = async (id: number) => {
    try {
      const res = await deleteUser(id);
      if (res.code === 200) {
        message.success(t('users.deleteSuccess'));
        fetchUsers();
      } else {
        message.error(res.message || t('users.deleteError'));
      }
    } catch {
      message.error(t('users.deleteError'));
    }
  };

  const handleResetPassword = async (values: { newPassword: string }) => {
    if (!currentUser) return;
    try {
      const res = await resetUserPassword(currentUser.id, values);
      if (res.code === 200) {
        message.success(t('users.resetSuccess'));
        setPasswordModalOpen(false);
        passwordForm.resetFields();
        setCurrentUser(null);
      } else {
        message.error(res.message || t('users.resetError'));
      }
    } catch {
      message.error(t('users.resetError'));
    }
  };

  const openEdit = (user: UserVO) => {
    setCurrentUser(user);
    editForm.setFieldsValue({
      email: user.email,
      role: user.role,
      status: user.status,
    });
    setEditModalOpen(true);
  };

  const openResetPassword = (user: UserVO) => {
    setCurrentUser(user);
    passwordForm.resetFields();
    setPasswordModalOpen(true);
  };

  const columns: ColumnsType<UserVO> = [
    {
      title: t('users.col.username'),
      dataIndex: 'username',
      key: 'username',
      render: (text: string) => (
        <Space>
          <UserOutlined style={{ color: token.colorPrimary }} />
          <span style={{ fontWeight: 500 }}>{text}</span>
        </Space>
      ),
    },
    {
      title: t('users.col.email'),
      dataIndex: 'email',
      key: 'email',
      responsive: ['md'],
      render: (text: string) => text || '-',
    },
    {
      title: t('users.col.role'),
      dataIndex: 'role',
      key: 'role',
      width: 120,
      render: (role: string) => (
        <Tag color={roleColorMap[role] || 'default'}>
          {t(`users.role.${role}`)}
        </Tag>
      ),
    },
    {
      title: t('users.col.status'),
      dataIndex: 'status',
      key: 'status',
      width: 80,
      render: (status: number) =>
        status === 1 ? (
          <Badge status="success" text={<span style={{ color: token.colorText }}>{t('users.status.enabled')}</span>} />
        ) : (
          <Badge status="error" text={<span style={{ color: token.colorText }}>{t('users.status.disabled')}</span>} />
        ),
    },
    {
      title: t('users.col.lastLogin'),
      dataIndex: 'lastLoginAt',
      key: 'lastLoginAt',
      width: 160,
      responsive: ['lg'],
      render: (v: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: t('users.col.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      responsive: ['lg'],
      render: (v: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: t('users.col.actions'),
      key: 'actions',
      width: isMobile ? 120 : 200,
      fixed: isMobile ? 'right' : undefined,
      render: (_, record) => (
        <Space size="small" wrap={isMobile}>
          <Tooltip title={isMobile ? t('common.edit') : undefined}>
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => openEdit(record)}
            >
              {!isMobile && t('common.edit')}
            </Button>
          </Tooltip>
          <Tooltip title={isMobile ? t('users.resetPassword') : undefined}>
            <Button
              type="link"
              size="small"
              icon={<KeyOutlined />}
              onClick={() => openResetPassword(record)}
            >
              {!isMobile && t('users.resetPassword')}
            </Button>
          </Tooltip>
          <Popconfirm
            title={t('users.confirmDelete')}
            onConfirm={() => handleDelete(record.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Tooltip title={isMobile ? t('common.delete') : undefined}>
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                {!isMobile && t('common.delete')}
              </Button>
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ 
        marginBottom: 16, 
        display: 'flex', 
        justifyContent: 'space-between', 
        alignItems: 'center',
        flexWrap: isMobile ? 'wrap' : 'nowrap',
        gap: 8,
      }}>
        <Title level={isMobile ? 5 : 4} style={{ margin: 0, color: token.colorText }}>
          {t('users.title')}
        </Title>
        <Button
          type="primary"
          size={isMobile ? 'small' : 'middle'}
          icon={<PlusOutlined />}
          onClick={() => {
            createForm.resetFields();
            setCreateModalOpen(true);
          }}
        >
          {t('users.createUser')}
        </Button>
      </div>

      <Card style={{ borderRadius: 12 }} styles={{ body: { padding: 0 } }}>
        <Table<UserVO>
          columns={columns}
          dataSource={users}
          rowKey="id"
          loading={loading}
          scroll={{ x: isMobile ? 600 : undefined }}
          size={isMobile ? 'small' : 'middle'}
          pagination={{
            pageSize: 20,
            total,
            showTotal: isMobile ? undefined : (total) => t('common.total', { count: total }),
            size: isMobile ? 'small' : undefined,
          }}
        />
      </Card>

      <Modal
        title={t('users.createUser')}
        open={createModalOpen}
        onCancel={() => setCreateModalOpen(false)}
        footer={null}
        destroyOnClose
      >
        <Form form={createForm} layout="vertical" onFinish={handleCreate}>
          <Form.Item
            name="username"
            label={t('users.field.username')}
            rules={[
              { required: true, message: t('users.field.usernameRequired') },
              { min: 3, max: 50, message: t('users.field.usernameLength') },
            ]}
          >
            <Input placeholder={t('users.field.usernamePlaceholder')} />
          </Form.Item>
          <Form.Item
            name="password"
            label={t('users.field.password')}
            rules={[
              { required: true, message: t('users.field.passwordRequired') },
              { min: 6, message: t('users.field.passwordMin') },
            ]}
          >
            <Input.Password placeholder={t('users.field.passwordPlaceholder')} />
          </Form.Item>
          <Form.Item name="email" label={t('users.field.email')}>
            <Input placeholder={t('users.field.emailPlaceholder')} />
          </Form.Item>
          <Form.Item
            name="role"
            label={t('users.field.role')}
            rules={[{ required: true, message: t('users.field.roleRequired') }]}
          >
            <Select placeholder={t('users.field.rolePlaceholder')}>
              <Select.Option value="super_admin">{t('users.role.super_admin')}</Select.Option>
              <Select.Option value="admin">{t('users.role.admin')}</Select.Option>
              <Select.Option value="operator">{t('users.role.operator')}</Select.Option>
              <Select.Option value="viewer">{t('users.role.viewer')}</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item>
            <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
              <Button onClick={() => setCreateModalOpen(false)}>{t('common.cancel')}</Button>
              <Button
                type="primary"
                htmlType="submit"
                >
                {t('common.create')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('users.editUser')}
        open={editModalOpen}
        onCancel={() => {
          setEditModalOpen(false);
          setCurrentUser(null);
        }}
        footer={null}
        destroyOnClose
      >
        <Form form={editForm} layout="vertical" onFinish={handleEdit}>
          <Form.Item name="email" label={t('users.field.email')}>
            <Input placeholder={t('users.field.emailPlaceholder')} />
          </Form.Item>
          <Form.Item name="role" label={t('users.field.role')}>
            <Select placeholder={t('users.field.rolePlaceholder')}>
              <Select.Option value="super_admin">{t('users.role.super_admin')}</Select.Option>
              <Select.Option value="admin">{t('users.role.admin')}</Select.Option>
              <Select.Option value="operator">{t('users.role.operator')}</Select.Option>
              <Select.Option value="viewer">{t('users.role.viewer')}</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item name="status" label={t('users.field.status')}>
            <Select placeholder={t('users.field.statusPlaceholder')}>
              <Select.Option value={1}>{t('users.status.enabled')}</Select.Option>
              <Select.Option value={0}>{t('users.status.disabled')}</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item>
            <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
              <Button onClick={() => setEditModalOpen(false)}>{t('common.cancel')}</Button>
              <Button
                type="primary"
                htmlType="submit"
                >
                {t('common.save')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('users.resetPasswordTitle', { username: currentUser?.username ?? '' })}
        open={passwordModalOpen}
        onCancel={() => {
          setPasswordModalOpen(false);
          setCurrentUser(null);
        }}
        footer={null}
        destroyOnClose
      >
        <Form form={passwordForm} layout="vertical" onFinish={handleResetPassword}>
          <Form.Item
            name="newPassword"
            label={t('users.field.newPassword')}
            rules={[
              { required: true, message: t('users.field.newPasswordRequired') },
              { min: 6, message: t('users.field.passwordMin') },
            ]}
          >
            <Input.Password placeholder={t('users.field.newPasswordPlaceholder')} />
          </Form.Item>
          <Form.Item>
            <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
              <Button onClick={() => setPasswordModalOpen(false)}>{t('common.cancel')}</Button>
              <Button
                type="primary"
                htmlType="submit"
                >
                {t('users.resetPassword')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default Users;
