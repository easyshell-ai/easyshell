import { useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Button, Space, Typography, theme, Tooltip } from 'antd';
import { ArrowLeftOutlined, CodeOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useResponsive } from '../../hooks/useResponsive';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { WebLinksAddon } from '@xterm/addon-web-links';
import '@xterm/xterm/css/xterm.css';

type ConnectionStatus = 'connecting' | 'connected' | 'disconnected';

const statusConfig: Record<ConnectionStatus, { color: string; textKey: string }> = {
  connecting: { color: '#faad14', textKey: 'terminal.connecting' },
  connected: { color: '#52c41a', textKey: 'terminal.connected' },
  disconnected: { color: '#ff4d4f', textKey: 'terminal.disconnected' },
};

const terminalTheme = {
  background: '#1a1b2e',
  foreground: '#e4e4e8',
  cursor: '#2563eb',
  selectionBackground: 'rgba(37, 99, 235, 0.3)',
  black: '#1a1b2e',
  red: '#ff5555',
  green: '#50fa7b',
  yellow: '#f1fa8c',
  blue: '#2563eb',
  magenta: '#bd93f9',
  cyan: '#8be9fd',
  white: '#e4e4e8',
  brightBlack: '#6272a4',
  brightRed: '#ff6e6e',
  brightGreen: '#69ff94',
  brightYellow: '#ffffa5',
  brightBlue: '#60a5fa',
  brightMagenta: '#d6acff',
  brightCyan: '#a4ffff',
  brightWhite: '#ffffff',
};

const TerminalPage: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const { isMobile } = useResponsive();
  const { agentId } = useParams<{ agentId: string }>();
  const navigate = useNavigate();
  const terminalRef = useRef<HTMLDivElement>(null);
  const termInstanceRef = useRef<Terminal | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);
  const [status, setStatus] = useState<ConnectionStatus>('connecting');

  useEffect(() => {
    if (!terminalRef.current || !agentId) return;

    const term = new Terminal({
      cursorBlink: true,
      fontSize: 14,
      fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', Menlo, Monaco, monospace",
      theme: terminalTheme,
      allowProposedApi: true,
      scrollback: 5000,
      convertEol: true,
    });

    const fitAddon = new FitAddon();
    const webLinksAddon = new WebLinksAddon();

    term.loadAddon(fitAddon);
    term.loadAddon(webLinksAddon);
    term.open(terminalRef.current);

    termInstanceRef.current = term;
    fitAddonRef.current = fitAddon;

    setTimeout(() => fitAddon.fit(), 100);

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/terminal/${agentId}`;
    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    term.writeln('\x1b[36m[' + t('terminal.system') + ']\x1b[0m ' + t('terminal.connectingHost'));

    ws.onopen = () => {
      term.writeln('\x1b[36m[' + t('terminal.system') + ']\x1b[0m ' + t('terminal.wsConnected'));
    };

    ws.onmessage = (event: MessageEvent) => {
      try {
        const msg = JSON.parse(event.data as string) as { type: string; data?: string };
        switch (msg.type) {
          case 'terminal_ready':
            setStatus('connected');
            setTimeout(() => {
              fitAddon.fit();
              ws.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows }));
            }, 200);
            break;
          case 'output':
            if (msg.data) {
              term.write(msg.data);
            }
            break;
          case 'error':
          case 'terminal_error':
            term.writeln(`\x1b[31m[${t('terminal.error')}]\x1b[0m ${msg.data || t('terminal.unknownError')}`);
            break;
        }
      } catch {
        term.write(event.data as string);
      }
    };

    ws.onclose = () => {
      setStatus('disconnected');
      term.writeln('\r\n\x1b[33m[' + t('terminal.system') + ']\x1b[0m ' + t('terminal.disconnectedRefresh'));
    };

    ws.onerror = () => {
      setStatus('disconnected');
      term.writeln('\r\n\x1b[31m[' + t('terminal.system') + ']\x1b[0m ' + t('terminal.connectionError'));
    };

    const inputDisposable = term.onData((data: string) => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'input', data }));
      }
    });

    const resizeDisposable = term.onResize(({ cols, rows }: { cols: number; rows: number }) => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'resize', cols, rows }));
      }
    });

    const handleResize = () => {
      if (fitAddonRef.current) {
        fitAddonRef.current.fit();
      }
    };
    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('resize', handleResize);
      inputDisposable.dispose();
      resizeDisposable.dispose();
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        ws.close();
      }
      term.dispose();
      termInstanceRef.current = null;
      wsRef.current = null;
      fitAddonRef.current = null;
    };
  }, [agentId]);

  const statusInfo = statusConfig[status];

  return (
    <div style={{ 
      display: 'flex', 
      flexDirection: 'column', 
      height: 'var(--content-inner-height)',
      minHeight: 0,
    }}>
      {/* Header */}
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: isMobile ? 8 : 12,
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

      {/* Terminal Container */}
      <div style={{
        flex: 1,
        borderRadius: isMobile ? 8 : 12,
        background: terminalTheme.background,
        border: `1px solid ${token.colorBorderSecondary}`,
        overflow: 'hidden',
        boxShadow: '0 4px 24px rgba(0, 0, 0, 0.2)',
        minHeight: 0,
      }}>
        <div
          ref={terminalRef}
          style={{
            width: '100%',
            height: '100%',
            padding: isMobile ? 4 : 8,
          }}
        />
      </div>
    </div>
  );
};

export default TerminalPage;
