import { useState } from 'react';
import { Form, Input, Button, Typography, message, theme } from 'antd';
import { UserOutlined, LockOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useResponsive } from '../../hooks/useResponsive';
import { login } from '../../api/auth';
import { setToken } from '../../api/request';
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
        // Access token only: kept in sessionStorage so it dies with the tab, and the
        // refresh token is never persisted in the browser (see api/request.ts).
        setToken(res.data.accessToken);
        message.success(t('login.success'));
        navigate('/');
      } else {
        message.error(res.message || t('login.failed'));
      }
    } catch (error) {
      // The API now answers with a real 401/403/429, so surface the reason instead of a
      // generic "check your input" message.
      const status = (error as { response?: { status?: number; data?: { message?: string } } })?.response;
      if (status?.status === 429) {
        message.error(t('login.tooManyAttempts'));
      } else if (status?.data?.message) {
        message.error(status.data.message);
      } else {
        message.error(t('login.errorCheck'));
      }
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
        padding: isMobile ? '28px 22px' : '36px 32px',
        borderRadius: 12,
        background: token.colorBgContainer,
        border: `1px solid ${token.colorBorderSecondary}`,
      }}>
        <div style={{ textAlign: 'center', marginBottom: isMobile ? 24 : 28 }}>
          <div style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 40,
            height: 40,
            borderRadius: 8,
            background: token.colorPrimary,
            marginBottom: 14,
          }}>
            <ThunderboltOutlined style={{ fontSize: 20, color: token.colorTextLightSolid }} />
          </div>
          <Typography.Title level={3} style={{
            margin: 0,
            fontWeight: 600,
            letterSpacing: '-0.02em',
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
                height: 40,
                borderRadius: 8,
                fontSize: 14,
                fontWeight: 500,
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
