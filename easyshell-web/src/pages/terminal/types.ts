export type ConnectionStatus = 'connecting' | 'connected' | 'disconnected';

export interface TerminalTab {
  id: string;
  label: string;
  agentId: string;
  status: ConnectionStatus;
}

export interface TerminalSettings {
  fontSize: number;
  fontFamily: string;
  theme: 'dark' | 'dracula' | 'solarized' | 'monokai' | 'light';
  scrollback: number;
}

export const MAX_TABS = 8;

export const statusConfig: Record<ConnectionStatus, { color: string; textKey: string }> = {
  connecting: { color: '#faad14', textKey: 'terminal.connecting' },
  connected: { color: '#52c41a', textKey: 'terminal.connected' },
  disconnected: { color: '#ff4d4f', textKey: 'terminal.disconnected' },
};

export const terminalTheme = {
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
