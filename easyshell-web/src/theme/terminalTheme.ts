import type { ITheme } from '@xterm/xterm';

/**
 * 终端 ANSI 16 色调色板（设计文档 frontend-redesign-design-v3.md §3.7，整表换源，0 个无来源 hex）。
 *
 * 同一张表两个消费方：XTERM_DARK / XTERM_LIGHT（ITheme 形状，喂 xterm）与 ANSI_PALETTE
 * （喂 W2 的日志查看器 ANSI 解析），由同一常量派生——"终端里看到的红"和"日志里看到的红"
 * 是同一个红。256 色由 xterm 内部按标准调色板处理，本文件只定义 16 色。
 */

/** ANSI 16 色的标准槽位顺序（30-37 / 90-97） */
export const ANSI_SLOTS = [
  'black', 'red', 'green', 'yellow', 'blue', 'magenta', 'cyan', 'white',
  'brightBlack', 'brightRed', 'brightGreen', 'brightYellow', 'brightBlue', 'brightMagenta', 'brightCyan', 'brightWhite',
] as const;

export type AnsiSlot = (typeof ANSI_SLOTS)[number];

type Palette16 = {
  background: string;
  foreground: string;
  cursor: string;
  selectionBackground: string;
} & Record<AnsiSlot, string>;

const DARK: Palette16 = {
  background: '#0d0d0d',
  foreground: '#cdcdcd',
  cursor: '#828fff',
  selectionBackground: 'rgba(94,106,210,0.32)',
  black: '#07080a',
  red: '#ff6161',
  green: '#59d499',
  yellow: '#ffc533',
  blue: '#57c1ff',
  magenta: '#eb367f',
  cyan: '#50e3c2',
  white: '#f4f4f6',
  brightBlack: '#9c9c9d',
  brightRed: '#ff8484',
  brightGreen: '#7eddaf',
  brightYellow: '#ffd15c',
  brightBlue: '#79cdff',
  brightMagenta: '#f0689f',
  brightCyan: '#aaffec',
  brightWhite: '#ffffff',
};

const LIGHT: Palette16 = {
  background: '#ffffff',
  foreground: '#4d4d4d',
  cursor: '#5e6ad2',
  selectionBackground: 'rgba(94,106,210,0.16)',
  black: '#171717',
  red: '#c50000',
  green: '#007d48',
  yellow: '#ab570a',
  blue: '#0761d1',
  magenta: '#4c2889',
  cyan: '#29bc9b',
  white: '#ffffff',
  brightBlack: '#707070',
  brightRed: '#ee0000',
  brightGreen: '#00673b',
  brightYellow: '#8c4708',
  brightBlue: '#0655b8',
  brightMagenta: '#6b01c2',
  brightCyan: '#1f8d74',
  brightWhite: '#fafafa',
};

const toITheme = (palette: Palette16): ITheme => ({
  background: palette.background,
  foreground: palette.foreground,
  cursor: palette.cursor,
  selectionBackground: palette.selectionBackground,
  black: palette.black,
  red: palette.red,
  green: palette.green,
  yellow: palette.yellow,
  blue: palette.blue,
  magenta: palette.magenta,
  cyan: palette.cyan,
  white: palette.white,
  brightBlack: palette.brightBlack,
  brightRed: palette.brightRed,
  brightGreen: palette.brightGreen,
  brightYellow: palette.brightYellow,
  brightBlue: palette.brightBlue,
  brightMagenta: palette.brightMagenta,
  brightCyan: palette.brightCyan,
  brightWhite: palette.brightWhite,
});

const toAnsi = (palette: Palette16): readonly string[] => ANSI_SLOTS.map((slot) => palette[slot]);

export const XTERM_DARK: ITheme = toITheme(DARK);
export const XTERM_LIGHT: ITheme = toITheme(LIGHT);

/** 日志查看器（非终端）解析 ANSI 时查的同一张表；索引 = ANSI 槽位顺序 */
export const ANSI_PALETTE: Record<'dark' | 'light', readonly string[]> = {
  dark: toAnsi(DARK),
  light: toAnsi(LIGHT),
};
