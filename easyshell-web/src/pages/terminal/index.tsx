import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Button, Space, Typography, theme, Tooltip } from 'antd';
import { ArrowLeftOutlined, CodeOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useResponsive } from '../../hooks/useResponsive';
import TerminalInstance from './components/TerminalInstance';
import type { TerminalInstanceRef } from './components/TerminalInstance';
import TerminalTabs from './components/TerminalTabs';
import TerminalToolbar from './components/TerminalToolbar';
import SearchBar from './components/SearchBar';
import type { TerminalTab, ConnectionStatus } from './types';
import { statusConfig, terminalTheme } from './types';
import FileManager from './components/FileManager';

let tabCounter = 1;

function createTab(agentId: string): TerminalTab {
  const id = crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2, 11)}`;
  const label = `Terminal ${tabCounter++}`;
  return { id, label, agentId, status: 'connecting' };
}

const TerminalPage: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const { isMobile } = useResponsive();
  const { agentId } = useParams<{ agentId: string }>();
  const navigate = useNavigate();

  const [tabs, setTabs] = useState<TerminalTab[]>(() =>
    agentId ? [createTab(agentId)] : []
  );
  const [activeTabId, setActiveTabId] = useState<string>(() =>
    tabs.length > 0 ? tabs[0].id : ''
  );
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [isFileManagerOpen, setIsFileManagerOpen] = useState(true);
  const [searchVisible, setSearchVisible] = useState(false);

  // Refs map for terminal instances
  const instanceRefs = useRef<Map<string, TerminalInstanceRef>>(new Map());
  const containerRef = useRef<HTMLDivElement>(null);

  const setInstanceRef = useCallback((tabId: string) => (ref: TerminalInstanceRef | null) => {
    if (ref) {
      instanceRefs.current.set(tabId, ref);
    } else {
      instanceRefs.current.delete(tabId);
    }
  }, []);

  const activeInstance = activeTabId ? instanceRefs.current.get(activeTabId) : null;

  // Tab management
  const handleAddTab = useCallback(() => {
    if (!agentId || tabs.length >= 8) return;
    const newTab = createTab(agentId);
    setTabs(prev => [...prev, newTab]);
    setActiveTabId(newTab.id);
  }, [agentId, tabs.length]);

  const handleCloseTab = useCallback((tabId: string) => {
    setTabs(prev => {
      const newTabs = prev.filter(t => t.id !== tabId);
      if (newTabs.length === 0) {
        navigate(-1);
        return prev;
      }
      return newTabs;
    });
    if (activeTabId === tabId) {
      setTabs(prev => {
        const idx = prev.findIndex(t => t.id === tabId);
        const newActive = prev[idx > 0 ? idx - 1 : idx + 1];
        if (newActive) setActiveTabId(newActive.id);
        return prev;
      });
    }
    instanceRefs.current.delete(tabId);
  }, [activeTabId, navigate]);

  const handleRenameTab = useCallback((tabId: string, newLabel: string) => {
    setTabs(prev => prev.map(t => t.id === tabId ? { ...t, label: newLabel } : t));
  }, []);

  const handleStatusChange = useCallback((tabId: string, status: ConnectionStatus) => {
    setTabs(prev => prev.map(t => t.id === tabId ? { ...t, status } : t));
  }, []);

  // Fullscreen
  const handleToggleFullscreen = useCallback(() => {
    if (!document.fullscreenElement) {
      containerRef.current?.requestFullscreen();
      setIsFullscreen(true);
    } else {
      document.exitFullscreen();
      setIsFullscreen(false);
    }
  }, []);

  useEffect(() => {
    const handler = () => setIsFullscreen(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', handler);
    return () => document.removeEventListener('fullscreenchange', handler);
  }, []);

  // Keyboard shortcuts
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.shiftKey && e.key === 'F') {
        e.preventDefault();
        setSearchVisible(v => !v);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  // Toolbar handlers
  const handleCopy = useCallback(() => {
    activeInstance?.copySelection();
  }, [activeInstance]);

  const handlePaste = useCallback(() => {
    activeInstance?.pasteFromClipboard();
  }, [activeInstance]);

  const handleToggleSearch = useCallback(() => {
    setSearchVisible(v => !v);
  }, []);

  const handleToggleFiles = useCallback(() => {
    setIsFileManagerOpen(v => !v);
  }, []);

  // Get active tab status for header display
  const activeTab = tabs.find(t => t.id === activeTabId);
  const statusInfo = statusConfig[activeTab?.status || 'disconnected'];

  return (
    <div
      ref={containerRef}
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: 'var(--content-inner-height)',
        minHeight: 0,
        background: isFullscreen ? terminalTheme.background : undefined,
      }}
    >
      {/* Header */}
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: isMobile ? 4 : 8,
        flexWrap: isMobile ? 'wrap' : 'nowrap',
        gap: 8,
      }}>
        <Space size={isMobile ? 'small' : 'middle'}>
          <Button
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate(-1)}
            size={isMobile ? 'small' : 'middle'}
          >
            {!isMobile && t('common.back')}
          </Button>
          <Typography.Title
            level={isMobile ? 5 : 4}
            style={{ margin: 0, color: token.colorText }}
          >
            <CodeOutlined style={{ marginRight: 8, color: token.colorPrimary }} />
            {!isMobile && t('terminal.title')}
          </Typography.Title>
        </Space>
        <Space>
          <div style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: isMobile ? '2px 8px' : '4px 12px',
            borderRadius: 6,
            background: token.colorBgContainer,
            border: `1px solid ${token.colorBorderSecondary}`,
            fontSize: isMobile ? 12 : 13,
          }}>
            <div style={{
              width: 8,
              height: 8,
              borderRadius: '50%',
              backgroundColor: statusInfo.color,
              boxShadow: `0 0 6px ${statusInfo.color}`,
            }} />
            <span style={{ color: token.colorTextSecondary }}>{t(statusInfo.textKey)}</span>
            {!isMobile && (
              <Tooltip title={agentId}>
                <span style={{ color: token.colorTextTertiary, fontSize: 12 }}>
                  {agentId ? `${agentId.substring(0, 16)}...` : ''}
                </span>
              </Tooltip>
            )}
          </div>
        </Space>
      </div>

      {/* Toolbar */}
      <TerminalToolbar
        onToggleFiles={handleToggleFiles}
        onSearch={handleToggleSearch}
        onAddTab={handleAddTab}
        onToggleFullscreen={handleToggleFullscreen}
        onCopy={handleCopy}
        onPaste={handlePaste}
        isFullscreen={isFullscreen}
        isFileManagerOpen={isFileManagerOpen}
        tabCount={tabs.length}
      />

      {/* Tabs */}
      <TerminalTabs
        tabs={tabs}
        activeTabId={activeTabId}
        onTabChange={setActiveTabId}
        onTabClose={handleCloseTab}
        onTabAdd={handleAddTab}
        onTabRename={handleRenameTab}
      />

      {/* Terminal area + File manager */}
      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        <div style={{
          flex: 1,
          position: 'relative',
          borderRadius: isMobile ? 8 : 12,
          background: terminalTheme.background,
          border: `1px solid ${token.colorBorderSecondary}`,
          overflow: 'hidden',
          boxShadow: '0 4px 24px rgba(0, 0, 0, 0.2)',
          minHeight: 0,
        }}>
          {tabs.map(tab => (
            <TerminalInstance
              key={tab.id}
              ref={setInstanceRef(tab.id)}
              tabId={tab.id}
              agentId={tab.agentId}
              isActive={tab.id === activeTabId}
              onStatusChange={handleStatusChange}
            />
          ))}
          {/* Search overlay */}
          <SearchBar
            searchAddon={activeInstance?.searchAddon || null}
            visible={searchVisible}
            onClose={() => setSearchVisible(false)}
          />
        </div>

        {/* Phase 2: FileManager sidebar */}
        {isFileManagerOpen && agentId && (
          <FileManager 
            agentId={agentId} 
            visible={isFileManagerOpen} 
            onClose={() => setIsFileManagerOpen(false)} 
          />
        )}
      </div>
    </div>
  );
};

export default TerminalPage;
