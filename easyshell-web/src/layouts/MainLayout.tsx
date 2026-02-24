import { useState, useEffect, useMemo, type CSSProperties } from 'react';
import { Outlet, useNavigate, useLocation, Link } from 'react-router-dom';
import { Layout, Menu, Dropdown, Space, Avatar, Tooltip, Breadcrumb, theme, Divider, Drawer } from 'antd';
import {
  DashboardOutlined,
  DesktopOutlined,
  CodeOutlined,
  PlayCircleOutlined,
  ClusterOutlined,
  AuditOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
  LogoutOutlined,
  SettingOutlined,
  TeamOutlined,
  ToolOutlined,
  ThunderboltOutlined,
  SunOutlined,
  MoonOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  MessageOutlined,
  ClockCircleOutlined,
  FileTextOutlined,
  CheckSquareOutlined,
  HomeOutlined,
  GlobalOutlined,
  BulbOutlined,
  FileProtectOutlined,
  MenuOutlined,
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import { useTheme } from '../contexts/ThemeContext';
import { useTranslation } from 'react-i18next';
import { getMe } from '../api/auth';
import { useResponsive } from '../hooks/useResponsive';

const { Header, Sider, Content } = Layout;

const getSelectedKeys = (pathname: string): string[] => {
  if (pathname === '/') return ['/'];
  if (pathname.startsWith('/system/')) return [pathname];
  if (pathname.startsWith('/host/')) return ['/host'];
  if (pathname.startsWith('/terminal/')) return ['/host'];
  if (pathname.startsWith('/ai/')) return [pathname];
  return ['/' + pathname.split('/')[1]];
};

const getOpenKeys = (pathname: string): string[] => {
  const keys: string[] = [];
  if (pathname.startsWith('/system')) keys.push('/system');
  if (pathname.startsWith('/ai')) keys.push('/ai');
  return keys;
};

const MainLayout: React.FC = () => {
  const [collapsed, setCollapsed] = useState(false);
  const [drawerVisible, setDrawerVisible] = useState(false);
  const [currentUser, setCurrentUser] = useState<{ username: string; role: string }>({ username: '', role: '' });
  const navigate = useNavigate();
  const location = useLocation();
  const [openKeys, setOpenKeys] = useState<string[]>(getOpenKeys(location.pathname));
  const { isDark, toggleTheme } = useTheme();
  const { token } = theme.useToken();
  const { t, i18n } = useTranslation();
  const { isMobile } = useResponsive();

  const routeLabelMap: Record<string, string> = useMemo(() => ({
    '/': t('nav.dashboard'),
    '/host': t('nav.host'),
    '/script': t('nav.script'),
    '/task': t('nav.task'),
    '/cluster': t('nav.cluster'),
    '/audit': t('nav.audit'),
    '/ai': t('nav.ai'),
    '/ai/chat': t('nav.ai_chat'),
    '/ai/scheduled': t('nav.ai_scheduled'),
    '/ai/reports': t('nav.ai_reports'),
    '/ai/approval': t('nav.ai_approval'),
    '/system': t('nav.system'),
    '/system/users': t('nav.system_users'),
    '/system/config': t('nav.system_config'),
    '/system/ai': t('nav.system_ai'),
    '/system/risk': t('nav.system_risk'),
    '/system/agents': t('nav.system_agents'),
    '/system/memory': t('nav.system_memory'),
    '/system/sop': t('nav.system_sop'),
    '/terminal': t('nav.terminal'),
  }), [t]);

  const menuItems: MenuProps['items'] = useMemo(() => {
    const groupLabelStyle: CSSProperties = {
      fontSize: 10,
      fontWeight: 700,
      letterSpacing: '0.08em',
      textTransform: 'uppercase' as const,
      color: isDark ? 'rgba(255,255,255,0.28)' : 'rgba(0,0,0,0.3)',
      padding: '20px 16px 6px',
      lineHeight: '16px',
      userSelect: 'none',
    };

    return [
      { type: 'group', label: <div style={groupLabelStyle}>{t('nav.group.overview')}</div>, children: [
        { key: '/', icon: <DashboardOutlined />, label: t('nav.dashboard') },
      ]},
      { type: 'group', label: <div style={groupLabelStyle}>{t('nav.group.infrastructure')}</div>, children: [
        { key: '/host', icon: <DesktopOutlined />, label: t('nav.host') },
        { key: '/cluster', icon: <ClusterOutlined />, label: t('nav.cluster') },
      ]},
      { type: 'group', label: <div style={groupLabelStyle}>{t('nav.group.devops')}</div>, children: [
        { key: '/script', icon: <CodeOutlined />, label: t('nav.script') },
        { key: '/task', icon: <PlayCircleOutlined />, label: t('nav.task') },
        { key: '/audit', icon: <AuditOutlined />, label: t('nav.audit') },
      ]},
      { type: 'group', label: <div style={groupLabelStyle}>{t('nav.group.intelligence')}</div>, children: [
        {
          key: '/ai',
          icon: <RobotOutlined />,
          label: t('nav.ai'),
          children: [
            { key: '/ai/chat', icon: <MessageOutlined />, label: t('nav.ai_chat') },
            { key: '/ai/scheduled', icon: <ClockCircleOutlined />, label: t('nav.ai_scheduled') },
            { key: '/ai/reports', icon: <FileTextOutlined />, label: t('nav.ai_reports') },
            { key: '/ai/approval', icon: <CheckSquareOutlined />, label: t('nav.ai_approval') },
          ],
        },
      ]},
      { type: 'group', label: <div style={groupLabelStyle}>{t('nav.group.administration')}</div>, children: [
        {
          key: '/system',
          icon: <SettingOutlined />,
          label: t('nav.system'),
          children: [
            { key: '/system/users', icon: <TeamOutlined />, label: t('nav.system_users') },
            { key: '/system/config', icon: <ToolOutlined />, label: t('nav.system_config') },
            { key: '/system/ai', icon: <RobotOutlined />, label: t('nav.system_ai') },
            { key: '/system/risk', icon: <SafetyCertificateOutlined />, label: t('nav.system_risk') },
            { key: '/system/agents', icon: <RobotOutlined />, label: t('nav.system_agents') },
            { key: '/system/memory', icon: <BulbOutlined />, label: t('nav.system_memory') },
            { key: '/system/sop', icon: <FileProtectOutlined />, label: t('nav.system_sop') },
          ],
        },
      ]},
    ];
  }, [t, isDark]);

  const breadcrumbItems = useMemo(() => {
    const items: Array<{ title: React.ReactNode; key: string }> = [
      { title: <Link to="/"><HomeOutlined /></Link>, key: 'home' },
    ];
    if (location.pathname === '/') return items;

    const segments = location.pathname.split('/').filter(Boolean);
    let currentPath = '';

    for (let i = 0; i < segments.length; i++) {
      currentPath += '/' + segments[i];
      const label = routeLabelMap[currentPath];
      if (label) {
        const isLast = i === segments.length - 1;
        items.push({
          title: isLast ? label : <Link to={currentPath}>{label}</Link>,
          key: currentPath,
        });
      } else {
        const parentPath = '/' + segments.slice(0, i).join('/');
        if (parentPath === '/host') {
          items.push({ title: t('nav.host_detail'), key: currentPath });
        } else {
          items.push({ title: segments[i], key: currentPath });
        }
      }
    }
    return items;
  }, [location.pathname, routeLabelMap, t]);

  useEffect(() => {
    const authToken = localStorage.getItem('token');
    if (!authToken) {
      navigate('/login');
      return;
    }
    getMe().then((res) => {
      if (res.code === 200 && res.data) {
        setCurrentUser({ username: res.data.username, role: res.data.role });
      }
    }).catch(() => {});
  }, [navigate]);

  useEffect(() => {
    setOpenKeys(getOpenKeys(location.pathname));
  }, [location.pathname]);

  const userMenuItems: MenuProps['items'] = useMemo(() => [
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: t('header.logout'),
      onClick: () => {
        localStorage.removeItem('token');
        navigate('/login');
      },
    },
  ], [t, navigate]);

  const accentGlow = isDark
    ? `0 0 80px ${token.colorPrimary}15, 0 0 160px ${token.colorPrimary}08`
    : 'none';

  const iconBtnStyle: CSSProperties = {
    width: 34,
    height: 34,
    borderRadius: 8,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    cursor: 'pointer',
    transition: 'all 200ms ease',
    background: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
    border: `1px solid ${isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.04)'}`,
  };

  const sidebarBackground = isDark
    ? 'linear-gradient(180deg, #111113 0%, #0c0c0e 100%)'
    : 'linear-gradient(180deg, #ffffff 0%, #fafafa 100%)';

  const handleMenuClick = ({ key }: { key: string }) => {
    navigate(key);
    if (isMobile) {
      setDrawerVisible(false);
    }
  };

  // Shared sidebar content for both desktop Sider and mobile Drawer
  const sidebarContent = (
    <>
      <div 
        className="sidebar-header"
        style={{
          justifyContent: collapsed && !isMobile ? 'center' : 'flex-start',
          padding: collapsed && !isMobile ? 0 : '0 20px',
          gap: 10,
          borderBottom: `1px solid ${isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)'}`,
        }}
      >
        <div style={{
          width: 34,
          height: 34,
          borderRadius: 10,
          background: `linear-gradient(135deg, ${token.colorPrimary}, ${token.colorPrimaryActive || '#1d4ed8'})`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexShrink: 0,
          boxShadow: `0 2px 12px ${token.colorPrimary}40`,
        }}>
          <ThunderboltOutlined style={{ fontSize: 16, color: '#fff' }} />
        </div>
        {(isMobile || !collapsed) && (
          <span style={{
            fontSize: 18,
            fontWeight: 800,
            color: token.colorText,
            letterSpacing: '-0.02em',
            whiteSpace: 'nowrap',
            background: isDark
              ? `linear-gradient(135deg, #fff, ${token.colorPrimary})`
              : `linear-gradient(135deg, #111, ${token.colorPrimary})`,
            WebkitBackgroundClip: 'text',
            WebkitTextFillColor: 'transparent',
          }}>
            EasyShell
          </span>
        )}
      </div>
      <div className="sidebar-menu-wrapper">
        <Menu
          theme={isDark ? 'dark' : 'light'}
          mode="inline"
          selectedKeys={getSelectedKeys(location.pathname)}
          openKeys={collapsed && !isMobile ? [] : openKeys}
          onOpenChange={setOpenKeys}
          items={menuItems}
          onClick={handleMenuClick}
          style={{
            background: 'transparent',
            border: 'none',
            marginTop: 4,
            padding: '0 8px',
          }}
        />
      </div>
      {(isMobile || !collapsed) && (
        <div className="sidebar-footer">
          <div style={{
            padding: '10px 12px',
            borderRadius: 10,
            background: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)',
            border: `1px solid ${isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.04)'}`,
            fontSize: 11,
            color: isDark ? 'rgba(255,255,255,0.3)' : 'rgba(0,0,0,0.3)',
            textAlign: 'center',
          }}>
            EasyShell v1.0
          </div>
        </div>
      )}
    </>
  );

  return (
    <Layout style={{ minHeight: '100vh' }} data-testid="main-layout">
      {/* Desktop Sider - hidden on mobile */}
      {!isMobile && (
        <Sider
          trigger={null}
          collapsible
          collapsed={collapsed}
          width={240}
          className="main-layout-sider"
          style={{
            background: sidebarBackground,
            borderRight: `1px solid ${isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)'}`,
            boxShadow: accentGlow,
          }}
          aria-label="Sidebar navigation"
        >
          {sidebarContent}
        </Sider>
      )}

      {/* Mobile Drawer */}
      {isMobile && (
        <Drawer
          placement="left"
          open={drawerVisible}
          onClose={() => setDrawerVisible(false)}
          width={280}
          styles={{
            body: { padding: 0 },
            header: { display: 'none' },
          }}
          className="mobile-nav-drawer"
        >
          <div
            style={{
              height: '100%',
              display: 'flex',
              flexDirection: 'column',
              background: sidebarBackground,
            }}
          >
            {sidebarContent}
          </div>
        </Drawer>
      )}

      <Layout style={{ background: isDark ? '#09090b' : '#f5f5f7' }}>
        <Header
          style={{
            height: 64,
            lineHeight: '64px',
            padding: isMobile ? '0 16px' : '0 24px',
            background: isDark
              ? 'rgba(14,14,16,0.8)'
              : 'rgba(255,255,255,0.8)',
            backdropFilter: 'blur(12px) saturate(180%)',
            WebkitBackdropFilter: 'blur(12px) saturate(180%)',
            borderBottom: `1px solid ${isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)'}`,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            position: 'sticky',
            top: 0,
            zIndex: 10,
          }}
          role="banner"
          aria-label="Top navigation bar"
        >
          <Space size={isMobile ? 12 : 16} align="center">
            {isMobile ? (
              <MenuOutlined 
                onClick={() => setDrawerVisible(true)} 
                style={{ fontSize: 18, color: token.colorTextSecondary, cursor: 'pointer' }} 
                aria-label={t('header.open_menu')} 
              />
            ) : collapsed ? (
              <MenuUnfoldOutlined 
                onClick={() => setCollapsed(false)} 
                style={{ fontSize: 16, color: token.colorTextSecondary, cursor: 'pointer' }} 
                aria-label={t('header.expand_sidebar')} 
              />
            ) : (
              <MenuFoldOutlined 
                onClick={() => setCollapsed(true)} 
                style={{ fontSize: 16, color: token.colorTextSecondary, cursor: 'pointer' }} 
                aria-label={t('header.collapse_sidebar')} 
              />
            )}
            <Divider type="vertical" style={{ height: 20, margin: 0 }} />
            {!isMobile && <Breadcrumb items={breadcrumbItems} style={{ margin: 0 }} />}
          </Space>
          <Space size={8}>
            <Tooltip title={i18n.language === 'zh-CN' ? t('header.switch_to_en') : t('header.switch_to_zh')}>
              <div
                onClick={() => {
                  const newLang = i18n.language === 'zh-CN' ? 'en-US' : 'zh-CN';
                  i18n.changeLanguage(newLang);
                  localStorage.setItem('locale', newLang);
                }}
                style={iconBtnStyle}
                aria-label="Toggle language"
                role="button"
              >
                <GlobalOutlined style={{ fontSize: 15, color: token.colorPrimary }} />
              </div>
            </Tooltip>
            <Tooltip title={isDark ? t('header.theme_light') : t('header.theme_dark')}>
              <div
                onClick={toggleTheme}
                style={iconBtnStyle}
                aria-label="Toggle theme"
                role="button"
                data-testid="theme-toggle"
              >
                {isDark
                  ? <SunOutlined style={{ fontSize: 15, color: '#faad14' }} />
                  : <MoonOutlined style={{ fontSize: 15, color: token.colorPrimary }} />
                }
              </div>
            </Tooltip>
            <div style={{ width: 1, height: 20, background: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.06)', margin: '0 4px' }} />
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
              <Space style={{ cursor: 'pointer', padding: '4px 8px', borderRadius: 8, transition: 'all 200ms', background: 'transparent' }} data-testid="user-menu">
                <Avatar
                  size={30}
                  icon={<UserOutlined />}
                  style={{
                    background: `linear-gradient(135deg, ${token.colorPrimary}, ${token.colorPrimaryActive || '#1d4ed8'})`,
                    boxShadow: `0 2px 8px ${token.colorPrimary}30`,
                  }}
                />
                {!isMobile && (
                  <span style={{ color: token.colorText, fontWeight: 600, fontSize: 13 }}>
                    {currentUser.username || '...'}
                  </span>
                )}
              </Space>
            </Dropdown>
          </Space>
        </Header>
        <Content
          className="main-layout-content"
          role="main"
          aria-label="Main content area"
        >
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default MainLayout;
