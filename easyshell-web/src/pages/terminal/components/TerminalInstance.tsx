import { useEffect, useRef, useImperativeHandle, forwardRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { WebLinksAddon } from '@xterm/addon-web-links';
import { SearchAddon } from '@xterm/addon-search';
import '@xterm/xterm/css/xterm.css';
import type { ConnectionStatus } from '../types';
import { terminalTheme } from '../types';

interface TerminalInstanceProps {
  tabId: string;
  agentId: string;
  isActive: boolean;
  onStatusChange: (tabId: string, status: ConnectionStatus) => void;
}

export interface TerminalInstanceRef {
  searchAddon: SearchAddon | null;
  copySelection: () => void;
  pasteFromClipboard: () => void;
  focus: () => void;
}

const TerminalInstance = forwardRef<TerminalInstanceRef, TerminalInstanceProps>(
  ({ tabId, agentId, isActive, onStatusChange }, ref) => {
    const { t } = useTranslation();
    const containerRef = useRef<HTMLDivElement>(null);
    const termRef = useRef<Terminal | null>(null);
    const wsRef = useRef<WebSocket | null>(null);
    const fitAddonRef = useRef<FitAddon | null>(null);
    const searchAddonRef = useRef<SearchAddon | null>(null);

    const copySelection = useCallback(() => {
      if (termRef.current) {
        const selection = termRef.current.getSelection();
        if (selection) {
          navigator.clipboard.writeText(selection);
        }
      }
    }, []);

    const pasteFromClipboard = useCallback(() => {
      navigator.clipboard.readText().then((text) => {
        if (wsRef.current?.readyState === WebSocket.OPEN) {
          wsRef.current.send(JSON.stringify({ type: 'input', data: text }));
        }
      });
    }, []);

    const focus = useCallback(() => {
      termRef.current?.focus();
    }, []);

    useImperativeHandle(ref, () => ({
      searchAddon: searchAddonRef.current,
      copySelection,
      pasteFromClipboard,
      focus,
    }), [copySelection, pasteFromClipboard, focus]);

    useEffect(() => {
      if (!containerRef.current || !agentId) return;

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
      const searchAddon = new SearchAddon();

      term.loadAddon(fitAddon);
      term.loadAddon(webLinksAddon);
      term.loadAddon(searchAddon);
      term.open(containerRef.current);

      termRef.current = term;
      fitAddonRef.current = fitAddon;
      searchAddonRef.current = searchAddon;

      setTimeout(() => fitAddon.fit(), 100);

      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = `${protocol}//${window.location.host}/ws/terminal/${agentId}`;
      const ws = new WebSocket(wsUrl);
      wsRef.current = ws;

      onStatusChange(tabId, 'connecting');
      term.writeln('\x1b[36m' + t('terminal.system') + '\x1b[0m ' + t('terminal.connectingHost'));

      ws.onopen = () => {
        term.writeln('\x1b[36m' + t('terminal.system') + '\x1b[0m ' + t('terminal.wsConnected'));
      };

      ws.onmessage = (event: MessageEvent) => {
        try {
          const msg = JSON.parse(event.data as string) as { type: string; data?: string };
          switch (msg.type) {
            case 'terminal_ready':
              onStatusChange(tabId, 'connected');
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
        onStatusChange(tabId, 'disconnected');
        term.writeln('\r\n\x1b[33m' + t('terminal.system') + '\x1b[0m ' + t('terminal.disconnectedRefresh'));
      };

      ws.onerror = () => {
        onStatusChange(tabId, 'disconnected');
        term.writeln('\r\n\x1b[31m' + t('terminal.system') + '\x1b[0m ' + t('terminal.connectionError'));
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
        termRef.current = null;
        wsRef.current = null;
        fitAddonRef.current = null;
        searchAddonRef.current = null;
      };
    }, [agentId, tabId]);

    // Refit when tab becomes active
    useEffect(() => {
      if (isActive && fitAddonRef.current) {
        setTimeout(() => {
          fitAddonRef.current?.fit();
          termRef.current?.focus();
        }, 50);
      }
    }, [isActive]);

    return (
      <div
        ref={containerRef}
        style={{
          width: '100%',
          height: '100%',
          padding: 8,
          display: isActive ? 'block' : 'none',
        }}
      />
    );
  }
);

TerminalInstance.displayName = 'TerminalInstance';

export default TerminalInstance;
