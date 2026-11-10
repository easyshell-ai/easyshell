import { describe, expect, it, beforeAll, afterEach } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from '../../../i18n/locales/zh-CN.json';
import enUS from '../../../i18n/locales/en-US.json';
import NodePalette from './NodePalette';

i18next.use(initReactI18next).init({
  resources: { 'zh-CN': { translation: zhCN }, 'en-US': { translation: enUS } },
  lng: 'en-US',
  fallbackLng: 'en-US',
  interpolation: { escapeValue: false },
});

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
});

afterEach(async () => {
  cleanup();
  await new Promise((resolve) => setTimeout(resolve, 0));
});

describe('NodePalette', () => {
  it('shows descriptions inline and never opens the old floating Tooltip', async () => {
    render(<NodePalette />);

    const item = screen.getByTestId('palette-llm') as HTMLElement;
    expect(item.textContent).toContain('Generate text or structured JSON');
    expect(screen.getByTestId('palette-classifier')).toBeTruthy();
    expect(screen.getByTestId('palette-code')).toBeTruthy();

    fireEvent.mouseEnter(item);
    fireEvent.focus(item);
    await waitFor(() => expect(item.className).toContain('wf-palette-item'));
    // 旧实现的白色不可读浮层已删除：悬停/聚焦后都不应出现 tooltip 容器
    expect(document.querySelector('.ant-tooltip')).toBeNull();
    expect(document.body.textContent).toContain('Generate text or structured JSON');
  });
});
