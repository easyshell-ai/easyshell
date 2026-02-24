import { useState } from 'react';
import { Form, Input, Button, Typography, message, theme } from 'antd';
import { UserOutlined, LockOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useResponsive } from '../../hooks/useResponsive';
import { login } from '../../api/auth';
import type { LoginRequest } from '../../types';

const Login: React.FC = () => {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const { isMobile } = useResponsive();

  const onFinish = async (values: LoginRequest) => {
    setLoading(true);
    try {
      const res = await login(values);
      if (res.code === 200 && res.data) {
        localStorage.setItem('token', res.data.accessToken);
        localStorage.setItem('refreshToken', res.data.refreshToken);
        message.success(t('login.success'));
        navigate('/');
      } else {
        message.error(res.message || t('login.failed'));
      }
    } catch {
      message.error(t('login.errorCheck'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{
      minHeight: '100vh',
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center',
      background: token.colorBgLayout,
      padding: isMobile ? 16 : 24,
    }}>
      {/* Login card */}
      <div style={{
        width: '100%',
        maxWidth: 400,
        padding: isMobile ? '32px 24px' : '48px 40px',
        borderRadius: 12,
        background: token.colorBgContainer,
        border: `1px solid ${token.colorBorderSecondary}`,
        boxShadow: '0 1px 3px rgba(0, 0, 0, 0.08)',
      }}>
        {/* Logo */}
        <div style={{ textAlign: 'center', marginBottom: isMobile ? 32 : 40 }}>
          <div style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: isMobile ? 48 : 56,
            height: isMobile ? 48 : 56,
            borderRadius: 12,
            background: token.colorPrimary,
            marginBottom: 16,
          }}>
            <ThunderboltOutlined style={{ fontSize: isMobile ? 24 : 28, color: '#fff' }} />
          </div>
          <Typography.Title level={isMobile ? 3 : 2} style={{
            margin: 0,
            fontWeight: 700,
            letterSpacing: 2,
            color: token.colorText,
          }}>
            EasyShell
          </Typography.Title>
          <Typography.Text style={{
            color: token.colorTextTertiary,
            fontSize: isMobile ? 13 : 14,
          }}>
            {t('login.subtitle')}
          </Typography.Text>
        </div>

        <Form onFinish={onFinish} size="large" autoComplete="off">
          <Form.Item name="username" rules={[{ required: true, message: t('login.usernameRequired') }]}>
            <Input
              prefix={<UserOutlined style={{ color: token.colorTextQuaternary }} />}
              placeholder={t('login.username')}
            />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: t('login.passwordRequired') }]}>
            <Input.Password
              prefix={<LockOutlined style={{ color: token.colorTextQuaternary }} />}
              placeholder={t('login.password')}
            />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0, marginTop: 8 }}>
            <Button
              type="primary"
              htmlType="submit"
              block
              loading={loading}
              style={{
                height: 48,
                borderRadius: 8,
                fontSize: 16,
                fontWeight: 600,
              }}
            >
              {t('login.submit')}
            </Button>
          </Form.Item>
        </Form>

        <div style={{
          textAlign: 'center',
          marginTop: 24,
          color: token.colorTextQuaternary,
          fontSize: 12,
        }}>
          © 2025 EasyShell · Powered by Spring Boot & React
        </div>
      </div>
    </div>
  );
};

export default Login;
