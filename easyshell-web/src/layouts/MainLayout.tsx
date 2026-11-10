import { useState, useEffect, useMemo, type CSSProperties } from 'react';
import { getToken, clearSession } from '../api/request';
import { Outlet, useNavigate, useLocation, Link } from 'react-router-dom';
import { Layout, Menu, Dropdown, Space, Avatar, Tooltip, Breadcrumb, theme, Divider, Drawer } from 'antd';
import {
  DashboardOutlined,
  DesktopOutlined,
  CodeOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  ClusterOutlined,
  AuditOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
  LogoutOutlined,
  SettingOutlined,
  TeamOutlined,
  ToolOutlined,
  ThunderboltFilled,
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
  QuestionCircleOutlined,
  LinkOutlined,
  ApiOutlined,
  PartitionOutlined,
  FieldTimeOutlined,
  SafetyOutlined,
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import { useTheme } from '../contexts/themeContext';
import { useTranslation } from 'react-i18next';
import { getMe } from '../api/auth';
import { useResponsive } from '../hooks/useResponsive';
import { useVersion } from '../hooks/useVersion';
import {
  SIDEBAR_WIDTH,
  SIDEBAR_COLLAPSED_WIDTH,
  HEADER_HEIGHT,
  MOBILE_DRAWER_WIDTH,
  ICON_BUTTON_SIZE,
} from './layoutConstants';

const { Header, Sider, Content } = Layout;

const getSelectedKeys = (pathname: string): string[] => {
  if (pathname === '/') return ['/'];
  if (pathname.startsWith('/system/')) return [pathname];
  if (pathname.startsWith('/host/')) return ['/host'];
  if (pathname.startsWith('/terminal/')) return ['/host'];
  if (pathname.startsWith('/script/')) return ['/script'];
  if (pathname === '/workflow/approvals') return ['/workflow/approvals'];
  if (pathname.startsWith('/workflow/run')) return ['/workflow/run'];
  if (pathname.startsWith('/workflow')) return ['/workflow'];
  if (pathname === '/ai/approval') return ['/ai/approval'];
  if (pathname.startsWith('/ai/')) return [pathname];
  return ['/' + pathname.split('/')[1]];
};

const getOpenKeys = (pathname: string): string[] => {
  const keys: string[] = [];
  if (pathname.startsWith('/system')) keys.push('/system');
  if (pathname.startsWith('/ai') && pathname !== '/ai/approval') keys.push('/ai');
  return keys;
};

const MainLayout: React.FC = () => {
  const [collapsed, setCollapsed] = useState(false);
  const [drawerVisible, setDrawerVisible] = useState(false);
  const [currentUser, setCurrentUser] = useState<{ username: string; role: string }>({ username: '', role: '' });
  const navigate = useNavigate();
  const location = useLocation();
  const [openKeys, setOpenKeys] = useState<string[]>(getOpenKeys(location.pathname));
  const [prevPathname, setPrevPathname] = useState(location.pathname);

  if (location.pathname !== prevPathname) {
    setPrevPathname(location.pathname);
    setOpenKeys(getOpenKeys(location.pathname));
  }
  const { isDark, toggleTheme, resetTheme } = useTheme();
  const { token } = theme.useToken();
  const { t, i18n } = useTranslation();
  const { isMobile } = useResponsive();
  const versionInfo = useVersion();

  const routeLabelMap: Record<string, string> = useMemo(() => ({
    '/': t('nav.dashboard'),
    '/host': t('nav.host'),
    '/script': t('nav.script'),
    '/script/new': t('script.createScript'),
    '/script/edit': t('script.editScript'),
    '/task': t('nav.task'),
    '/workflow': t('nav.workflow'),
    '/workflow/editor': t('nav.workflow_editor'),
    '/workflow/run': t('nav.workflow_run'),
    '/workflow/approvals': t('nav.workflow_approvals'),
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
    '/system/bot': t('nav.system_bot'),
    '/system/risk': t('nav.system_risk'),
    '/system/agents': t('nav.system_agents'),
    '/system/memory': t('nav.system_memory'),
    '/system/sop': t('nav.system_sop'),
    '/terminal': t('nav.terminal'),
  }), [t]);

  const menuItems: MenuProps['items'] = useMemo(() => {
    // 分组标题（§4.6）：11px / 500 / 常规大小写；字号与行高由 Menu 的
    // groupTitleFontSize:11 / groupTitleLineHeight:16 token 提供，这里只补内边距与取色。
    // 颜色用 colorTextTertiary 而不是 Menu.darkGroupTitleColor：W1 锁定的那个 token 落在
    // ash 档（#6a6b6c / #9a9a9a），§4.9.2-5 要求 11px 文字必须用 mute 档（7.30 / 4.95）。
    const groupLabelStyle: CSSProperties = {
      padding: 0,
      lineHeight: '16px',
      userSelect: 'none',
      color: token.colorTextTertiary,
      letterSpacing: '0.04em',
      textTransform: 'uppercase',
      fontSize: 11,
      fontWeight: 500,
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
        { key: '/workflow', icon: <PartitionOutlined />, label: t('nav.workflow') },
        { key: '/workflow/run', icon: <FieldTimeOutlined />, label: t('nav.workflow_run') },
        { key: '/audit', icon: <AuditOutlined />, label: t('nav.audit') },
      ]},
      { type: 'group', label: <div style={groupLabelStyle}>{t('nav.group.approval')}</div>, children: [
        { key: '/ai/approval', icon: <CheckSquareOutlined />, label: t('nav.ai_approval') },
        { key: '/workflow/approvals', icon: <SafetyOutlined />, label: t('nav.workflow_approvals') },
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
            { key: '/system/bot', icon: <ApiOutlined />, label: t('nav.system_bot') },
            { key: '/system/risk', icon: <SafetyCertificateOutlined />, label: t('nav.system_risk') },
            { key: '/system/agents', icon: <RobotOutlined />, label: t('nav.system_agents') },
            { key: '/system/memory', icon: <BulbOutlined />, label: t('nav.system_memory') },
            { key: '/system/sop', icon: <FileProtectOutlined />, label: t('nav.system_sop') },
          ],
        },
      ]},
    ];
  }, [t, token.colorTextTertiary]);

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
    const authToken = getToken();
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

  const userMenuItems: MenuProps['items'] = useMemo(() => [
    {
      key: 'reset-theme',
      icon: <ReloadOutlined />,
      label: t('header.reset_theme_default', i18n.language === 'zh-CN' ? '重置为默认主题' : 'Reset to default theme'),
      onClick: resetTheme,
    },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: t('header.logout'),
      onClick: () => {
        clearSession();
        navigate('/login');
      },
    },
  ], [t, navigate, resetTheme, i18n.language]);

  /** 顶栏图标按钮（§4.6）：统一 28×28 + colorBorder 边，收进一个常量（原 34×34 是 §4.10.2 白名单必改项） */
  const iconBtnStyle: CSSProperties = {
    width: ICON_BUTTON_SIZE,
    height: ICON_BUTTON_SIZE,
    borderRadius: token.borderRadiusSM,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    cursor: 'pointer',
    transition: 'all 0.1s ease',
    background: token.colorFillTertiary,
    border: `1px solid ${token.colorBorder}`,
  };

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
          borderBottom: `1px solid ${token.colorSplit}`,
        }}
      >
        {/* Logo（§4.6）：去掉渐变文字与彩色 glow，改为 24×24 colorPrimary 圆角方块 + 白色闪电 */}
        <div style={{
          width: 24,
          height: 24,
          borderRadius: 6,
          background: token.colorPrimary,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexShrink: 0,
        }}>
          <ThunderboltFilled style={{ fontSize: 14, color: token.colorTextLightSolid }} />
        </div>
        {(isMobile || !collapsed) && (
          <span style={{
            fontSize: 15,
            fontWeight: 600,
            color: token.colorText,
            whiteSpace: 'nowrap',
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
            padding: '8px 12px',
            borderRadius: 6,
            background: token.colorFillQuaternary,
            border: `1px solid ${token.colorBorderSecondary}`,
            fontSize: 11,
            color: token.colorTextTertiary,
            textAlign: 'center',
          }}>
            EasyShell {versionInfo ? `v${versionInfo.agentVersion}` : ''}
          </div>
        </div>
      )}
    </>
  );

  return (
    <Layout style={{ minHeight: '100vh' }} data-testid="main-layout">
      {/* Skip link（§4.9.3）：键盘用户第一条 Tab 就能跳过侧边栏直达内容 */}
      <a href="#main-content" className="es-skip-link">
        {t('header.skip_to_content', i18n.language === 'zh-CN' ? '跳到主内容' : 'Skip to main content')}
      </a>

      {/* Desktop Sider - hidden on mobile */}
      {!isMobile && (
        <Sider
          trigger={null}
          collapsible
          collapsed={collapsed}
          width={SIDEBAR_WIDTH}
          collapsedWidth={SIDEBAR_COLLAPSED_WIDTH}
          className="main-layout-sider"
          style={{
            borderRight: `1px solid ${token.colorSplit}`,
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
          width={MOBILE_DRAWER_WIDTH}
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
              background: token.colorBgLayout,
            }}
          >
            {sidebarContent}
          </div>
        </Drawer>
      )}

      <Layout style={{ background: token.colorBgLayout }}>
        <Header
          style={{
            height: HEADER_HEIGHT,
            padding: isMobile ? '0 16px' : '0 20px',
            background: token.colorBgLayout,
            backdropFilter: 'blur(12px) saturate(180%)',
            WebkitBackdropFilter: 'blur(12px) saturate(180%)',
            borderBottom: `1px solid ${token.colorSplit}`,
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
            <Tooltip title={t('header.website')}>
              <a href="https://easyshell.ai" target="_blank" rel="noopener noreferrer" style={{ textDecoration: 'none' }}>
                <div style={iconBtnStyle} role="button" aria-label="Website">
                  <LinkOutlined style={{ fontSize: 15, color: token.colorTextSecondary }} />
                </div>
              </a>
            </Tooltip>
            <Tooltip title={t('header.docs')}>
              <a href="https://docs.easyshell.ai" target="_blank" rel="noopener noreferrer" style={{ textDecoration: 'none' }}>
                <div style={iconBtnStyle} role="button" aria-label="Documentation">
                  <QuestionCircleOutlined style={{ fontSize: 15, color: token.colorTextSecondary }} />
                </div>
              </a>
            </Tooltip>
            <Tooltip title={t('header.discord')}>
              <a href="https://discord.gg/akQqRgNB6t" target="_blank" rel="noopener noreferrer" style={{ textDecoration: 'none' }}>
                <div style={iconBtnStyle} role="button" aria-label="Discord">
                  <svg viewBox="0 0 24 24" width="15" height="15" fill={token.colorTextSecondary}><path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028 14.09 14.09 0 0 0 1.226-1.994.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.157 2.418z"/></svg>
                </div>
              </a>
            </Tooltip>
            <div style={{ width: 1, height: 20, background: token.colorBorderSecondary, margin: '0 4px' }} />
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
                  ? <SunOutlined style={{ fontSize: 15, color: token.colorWarning }} />
                  : <MoonOutlined style={{ fontSize: 15, color: token.colorPrimary }} />
                }
              </div>
            </Tooltip>
            <div style={{ width: 1, height: 20, background: token.colorBorderSecondary, margin: '0 4px' }} />
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
              <Space style={{ cursor: 'pointer', padding: '4px 8px', borderRadius: 8, transition: 'all 0.1s ease', background: 'transparent' }} data-testid="user-menu">
                <Avatar
                  size={ICON_BUTTON_SIZE}
                  icon={<UserOutlined />}
                  style={{ background: token.colorPrimary }}
                />
                {!isMobile && (
                  <span style={{ color: token.colorText, fontWeight: 500, fontSize: 13 }}>
                    {currentUser.username || '...'}
                  </span>
                )}
              </Space>
            </Dropdown>
          </Space>
        </Header>
        <Content
          id="main-content"
          className="main-layout-content"
          role="main"
          aria-label="Main content area"
        >
          {/* 内容区居中容器（§4.10.4）：1600px 上限，超宽视口里内容居中 */}
          <div className="es-content">
            <Outlet />
          </div>
        </Content>
      </Layout>
    </Layout>
  );
};

export default MainLayout;
