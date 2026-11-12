/**
 * ANSI 转义解析（S6：必须产 React 元素，全仓维持 0 处 dangerouslySetInnerHTML）。
 *
 * agent 原始日志带 SGR 颜色/加粗序列。这里只解析 \x1b[...m（其余 CSI 序列直接丢弃），
 * 输出带样式描述符的文本段，由组件渲染成 <span style>——不产生 HTML 字符串，XSS 面为零。
 */

export interface AnsiStyle {
  color?: string;
  background?: string;
  bold?: boolean;
  dim?: boolean;
  italic?: boolean;
  underline?: boolean;
  inverse?: boolean;
}

export interface AnsiSegment {
  text: string;
  style: AnsiStyle;
}

// eslint-disable-next-line no-control-regex -- 解析 ANSI 转义就必须匹配 ESC (0x1b)
const ANSI_PATTERN = /\x1b\[([0-9;?]*)([A-Za-z])/g;

/** 30-37 / 90-97 -> CSS 颜色；只映射标准 16 色，其余按默认色处理 */
const FOREGROUND: Record<number, string> = {
  30: '#000000', 31: '#cd3131', 32: '#0dbc79', 33: '#e5e510',
  34: '#2472c8', 35: '#bc3fbc', 36: '#11a8cd', 37: '#e5e5e5',
  90: '#666666', 91: '#f14c4c', 92: '#23d18b', 93: '#f5f543',
  94: '#3b8eea', 95: '#d670d6', 96: '#29b8db', 97: '#ffffff',
};

const BACKGROUND: Record<number, string> = {
  40: '#000000', 41: '#cd3131', 42: '#0dbc79', 43: '#e5e510',
  44: '#2472c8', 45: '#bc3fbc', 46: '#11a8cd', 47: '#e5e5e5',
  100: '#666666', 101: '#f14c4c', 102: '#23d18b', 103: '#f5f543',
  104: '#3b8eea', 105: '#d670d6', 106: '#29b8db', 107: '#ffffff',
};

/** 解析一行日志为带样式的文本段（无转义时返回单段） */
export function parseAnsi(line: string): AnsiSegment[] {
  if (!line) return [];
  const segments: AnsiSegment[] = [];
  let style: AnsiStyle = {};
  let cursor = 0;

  const push = (text: string) => {
    if (!text) return;
    const previous = segments[segments.length - 1];
    if (previous && sameStyle(previous.style, style)) {
      previous.text += text;
      return;
    }
    segments.push({ text, style: { ...style } });
  };

  ANSI_PATTERN.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = ANSI_PATTERN.exec(line)) !== null) {
    const [raw, params, final] = match;
    push(line.slice(cursor, match.index));
    cursor = match.index + raw.length;
    if (final === 'm') {
      style = applySgr(style, params);
    }
    // 其余 CSI（光标移动/清屏等）在只读日志里直接丢弃
  }
  push(line.slice(cursor));
  return segments;
}

function applySgr(style: AnsiStyle, params: string): AnsiStyle {
  const codes = params
    .split(';')
    .map((part) => Number.parseInt(part, 10))
    .filter((code) => !Number.isNaN(code));
  const next: AnsiStyle = { ...style };
  if (codes.length === 0) return {};
  for (const code of codes) {
    if (code === 0) return {};
    if (code === 1) next.bold = true;
    else if (code === 2) next.dim = true;
    else if (code === 3) next.italic = true;
    else if (code === 4) next.underline = true;
    else if (code === 7) next.inverse = true;
    else if (code === 22) { next.bold = false; next.dim = false; }
    else if (code === 23) next.italic = false;
    else if (code === 24) next.underline = false;
    else if (code === 27) next.inverse = false;
    else if (code === 39) next.color = undefined;
    else if (code === 49) next.background = undefined;
    else if (FOREGROUND[code]) next.color = FOREGROUND[code];
    else if (BACKGROUND[code]) next.background = BACKGROUND[code];
  }
  return next;
}

function sameStyle(a: AnsiStyle, b: AnsiStyle): boolean {
  return (
    a.color === b.color &&
    a.background === b.background &&
    a.bold === b.bold &&
    a.dim === b.dim &&
    a.italic === b.italic &&
    a.underline === b.underline &&
    a.inverse === b.inverse
  );
}

/** 去掉所有转义序列（用于复制/搜索） */
export function stripAnsi(line: string): string {
  return line.replace(ANSI_PATTERN, '');
}
