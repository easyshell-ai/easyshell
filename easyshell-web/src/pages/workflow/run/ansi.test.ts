import { describe, expect, it } from 'vitest';
import { parseAnsi, stripAnsi } from './ansi';

describe('parseAnsi', () => {
  it('returns plain text as a single segment', () => {
    expect(parseAnsi('hello world')).toEqual([{ text: 'hello world', style: {} }]);
  });

  it('splits styled runs and keeps the raw text intact', () => {
    const segments = parseAnsi('\x1b[31mred\x1b[0m plain');
    expect(segments).toEqual([
      { text: 'red', style: { color: '#cd3131' } },
      { text: ' plain', style: {} },
    ]);
  });

  it('merges adjacent segments with identical style', () => {
    const segments = parseAnsi('\x1b[1mbold\x1b[1mmore');
    expect(segments).toEqual([{ text: 'boldmore', style: { bold: true } }]);
  });

  it('keeps different styles in separate segments', () => {
    const segments = parseAnsi('\x1b[1mbold\x1b[31mred-bold');
    expect(segments).toHaveLength(2);
    expect(segments[1]).toEqual({ text: 'red-bold', style: { bold: true, color: '#cd3131' } });
  });

  it('handles combined SGR parameters and background colours', () => {
    const segments = parseAnsi('\x1b[1;4;44;93mx');
    expect(segments[0].style).toEqual({ bold: true, underline: true, background: '#2472c8', color: '#f5f543' });
  });

  it('resets on SGR 0 and on individual attribute resets', () => {
    // SGR 22 同时清 bold/dim（与 xterm 行为一致）
    expect(parseAnsi('\x1b[1ma\x1b[22mb')[1].style).toEqual({ bold: false, dim: false });
    expect(parseAnsi('\x1b[31ma\x1b[39mb')[1].style).toEqual({ color: undefined });
  });

  it('drops non-SGR CSI sequences instead of leaking escapes', () => {
    const segments = parseAnsi('a\x1b[2Kb\x1b[10;5Hc');
    expect(segments.map((s) => s.text).join('')).toBe('abc');
  });

  it('never emits HTML markup', () => {
    const segments = parseAnsi('<script>alert(1)</script>\x1b[31m');
    expect(segments.some((s) => s.text.includes('<script>'))).toBe(true);
    expect(segments.some((s) => /<[a-z]/i.test(JSON.stringify(s.style)))).toBe(false);
  });
});

describe('stripAnsi', () => {
  it('removes every escape sequence', () => {
    expect(stripAnsi('\x1b[31mred\x1b[0m')).toBe('red');
  });
});
