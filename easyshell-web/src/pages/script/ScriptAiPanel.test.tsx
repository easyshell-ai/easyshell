import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import ScriptAiPanel from './ScriptAiPanel';

vi.mock('react-i18next', async (importOriginal) => ({
  ...await importOriginal<typeof import('react-i18next')>(),
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'en-US' } }),
}));

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: (media: string) => ({
    matches: false, media, addListener: () => {}, removeListener: () => {},
    addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false,
  }) });
  if (!('ResizeObserver' in window)) {
    (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
});
afterEach(cleanup);

describe('ScriptAiPanel external data warning', () => {
  it.each([undefined, 'echo existing script'])('shows the warning before generating with script %s', (currentScript) => {
    render(<ScriptAiPanel scriptType="shell" currentScript={currentScript} onApply={vi.fn()} />);
    const warning = screen.getByRole('alert');
    expect(warning.textContent).toContain('script.ai.externalDataWarningTitle');
    expect(warning.textContent).toContain('script.ai.externalDataWarningDescription');
    expect(screen.getByRole('button', { name: /script.ai.generate/ })).toBeTruthy();
  });
});
