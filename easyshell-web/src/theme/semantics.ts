/**
 * 业务语义色（设计文档 frontend-redesign-design-v3.md §3.4）——全站唯一权威。
 *
 * 消费方式：读 `--es-sem-*` CSS 变量（ThemeContext 在 isDark 变化时注入 documentElement），
 * 不在组件里直接 import hex。alpha 档位：深色底 .15 / 边 .30，浅色底 .06 / 边 .22（§3.2）。
 */

export type SemanticKey =
  | 'online'
  | 'offline'
  | 'unstable'
  | 'pending'
  | 'queued'
  | 'running'
  | 'waiting'
  | 'approvalWaiting'
  | 'succeeded'
  | 'partial'
  | 'timeout'
  | 'compensating'
  | 'interrupted'
  | 'failed'
  | 'skipped'
  | 'cancelled'
  | 'riskLow'
  | 'riskMedium'
  | 'riskHigh'
  | 'riskBanned'
  | 'versionMismatch';

export type SemanticMode = 'dark' | 'light';

export interface SemanticColors {
  /** 文字 / 图标色 */
  text: string;
  /** 底色 */
  bg: string;
  /** 边框色 */
  border: string;
}

export const SEMANTIC: Record<SemanticKey, Record<SemanticMode, SemanticColors>> = {
  online: {
    dark: { text: '#59d499', bg: 'rgba(89,212,153,0.15)', border: 'rgba(89,212,153,0.30)' },
    light: { text: '#007d48', bg: 'rgba(0,125,72,0.06)', border: 'rgba(0,125,72,0.22)' },
  },
  offline: {
    dark: { text: '#ff6161', bg: 'rgba(255,97,97,0.15)', border: 'rgba(255,97,97,0.30)' },
    light: { text: '#c50000', bg: 'rgba(197,0,0,0.06)', border: 'rgba(197,0,0,0.22)' },
  },
  unstable: {
    dark: { text: '#ffc533', bg: 'rgba(255,197,51,0.15)', border: 'rgba(255,197,51,0.30)' },
    light: { text: '#ab570a', bg: 'rgba(171,87,10,0.06)', border: 'rgba(171,87,10,0.22)' },
  },
  pending: {
    dark: { text: '#cdcdcd', bg: '#141516', border: '#242728' },
    light: { text: '#4d4d4d', bg: '#f0f0f0', border: '#ebebeb' },
  },
  queued: {
    dark: { text: '#cdcdcd', bg: '#141516', border: '#242728' },
    light: { text: '#4d4d4d', bg: '#f0f0f0', border: '#ebebeb' },
  },
  running: {
    dark: { text: '#57c1ff', bg: 'rgba(87,193,255,0.15)', border: 'rgba(87,193,255,0.30)' },
    light: { text: '#0761d1', bg: 'rgba(7,97,209,0.06)', border: 'rgba(7,97,209,0.22)' },
  },
  waiting: {
    dark: { text: '#828fff', bg: 'rgba(130,143,255,0.15)', border: 'rgba(130,143,255,0.30)' },
    light: { text: '#5e6ad2', bg: 'rgba(94,106,210,0.06)', border: 'rgba(94,106,210,0.22)' },
  },
  approvalWaiting: {
    dark: { text: '#828fff', bg: 'rgba(130,143,255,0.15)', border: 'rgba(130,143,255,0.30)' },
    light: { text: '#5e6ad2', bg: 'rgba(94,106,210,0.06)', border: 'rgba(94,106,210,0.22)' },
  },
  succeeded: {
    dark: { text: '#59d499', bg: 'rgba(89,212,153,0.15)', border: 'rgba(89,212,153,0.30)' },
    light: { text: '#007d48', bg: 'rgba(0,125,72,0.06)', border: 'rgba(0,125,72,0.22)' },
  },
  partial: {
    dark: { text: '#ffc533', bg: 'rgba(255,197,51,0.15)', border: 'rgba(255,197,51,0.30)' },
    light: { text: '#ab570a', bg: 'rgba(171,87,10,0.06)', border: 'rgba(171,87,10,0.22)' },
  },
  timeout: {
    dark: { text: '#ffc533', bg: 'rgba(255,197,51,0.15)', border: 'rgba(255,197,51,0.30)' },
    light: { text: '#ab570a', bg: 'rgba(171,87,10,0.06)', border: 'rgba(171,87,10,0.22)' },
  },
  compensating: {
    dark: { text: '#ffc533', bg: 'rgba(255,197,51,0.15)', border: 'rgba(255,197,51,0.30)' },
    light: { text: '#ab570a', bg: 'rgba(171,87,10,0.06)', border: 'rgba(171,87,10,0.22)' },
  },
  interrupted: {
    dark: { text: '#ffc533', bg: 'rgba(255,197,51,0.15)', border: 'rgba(255,197,51,0.30)' },
    light: { text: '#ab570a', bg: 'rgba(171,87,10,0.06)', border: 'rgba(171,87,10,0.22)' },
  },
  failed: {
    dark: { text: '#ff6161', bg: 'rgba(255,97,97,0.15)', border: 'rgba(255,97,97,0.30)' },
    light: { text: '#c50000', bg: 'rgba(197,0,0,0.06)', border: 'rgba(197,0,0,0.22)' },
  },
  skipped: {
    dark: { text: '#9c9c9d', bg: '#141516', border: '#242728' },
    light: { text: '#707070', bg: '#f0f0f0', border: '#ebebeb' },
  },
  cancelled: {
    dark: { text: '#9c9c9d', bg: '#141516', border: '#242728' },
    light: { text: '#707070', bg: '#f0f0f0', border: '#ebebeb' },
  },
  riskLow: {
    dark: { text: '#59d499', bg: 'rgba(89,212,153,0.15)', border: 'rgba(89,212,153,0.30)' },
    light: { text: '#007d48', bg: 'rgba(0,125,72,0.06)', border: 'rgba(0,125,72,0.22)' },
  },
  riskMedium: {
    dark: { text: '#ffc533', bg: 'rgba(255,197,51,0.15)', border: 'rgba(255,197,51,0.30)' },
    light: { text: '#ab570a', bg: 'rgba(171,87,10,0.06)', border: 'rgba(171,87,10,0.22)' },
  },
  riskHigh: {
    dark: { text: '#ff6161', bg: 'rgba(255,97,97,0.15)', border: 'rgba(255,97,97,0.30)' },
    light: { text: '#c50000', bg: 'rgba(197,0,0,0.06)', border: 'rgba(197,0,0,0.22)' },
  },
  riskBanned: {
    dark: { text: '#c0a8dd', bg: 'rgba(192,168,221,0.15)', border: 'rgba(192,168,221,0.30)' },
    light: { text: '#4c2889', bg: 'rgba(76,40,137,0.06)', border: 'rgba(76,40,137,0.22)' },
  },
  versionMismatch: {
    dark: { text: '#57c1ff', bg: 'rgba(87,193,255,0.15)', border: 'rgba(87,193,255,0.30)' },
    light: { text: '#0761d1', bg: 'rgba(7,97,209,0.06)', border: 'rgba(7,97,209,0.22)' },
  },
};

/** CSS 变量名前缀：--es-sem-<key>-<text|bg|border>（camelCase key 转 kebab-case） */
export const SEMANTIC_CSS_PREFIX = 'es-sem';

const kebab = (key: string): string => key.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();

export function semanticVar(key: SemanticKey, part: keyof SemanticColors = 'text'): string {
  return `var(--${SEMANTIC_CSS_PREFIX}-${kebab(key)}-${part})`;
}

/** 把当前模式的语义色写到 documentElement（设计 §3.5：唯一保留的手写注入） */
export function applySemanticColors(mode: SemanticMode): void {
  if (typeof document === 'undefined') return;
  for (const [key, modes] of Object.entries(SEMANTIC)) {
    const colors = modes[mode];
    document.documentElement.style.setProperty(`--${SEMANTIC_CSS_PREFIX}-${kebab(key)}-text`, colors.text);
    document.documentElement.style.setProperty(`--${SEMANTIC_CSS_PREFIX}-${kebab(key)}-bg`, colors.bg);
    document.documentElement.style.setProperty(`--${SEMANTIC_CSS_PREFIX}-${kebab(key)}-border`, colors.border);
  }
}
