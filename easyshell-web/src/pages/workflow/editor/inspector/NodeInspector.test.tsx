import { describe, expect, it, vi, beforeAll, afterEach } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from '../../../../i18n/locales/zh-CN.json';
import enUS from '../../../../i18n/locales/en-US.json';
import { NodeInspector } from './NodeInspector';
import type { WorkflowDefinition, WorkflowNode } from '../../types';

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
  if (!('ResizeObserver' in window)) {
    (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
});

afterEach(async () => {
  cleanup();
  await new Promise((resolve) => setTimeout(resolve, 0));
});

const node = (key: string, type: WorkflowNode['type'], config: Record<string, unknown> = {}): WorkflowNode => ({
  key,
  type,
  name: key,
  position: { x: 0, y: 0 },
  config,
});

const definition = (nodes: WorkflowNode[]): WorkflowDefinition => ({
  schemaVersion: 1,
  defaultOnUpstreamFailure: 'skip',
  onRejection: 'abort',
  variables: {},
  nodes,
  edges: [],
});

describe('NodeInspector', () => {
  it('switches between config/data/docs tabs and keeps the save footer', async () => {
    const onSave = vi.fn();
    const wait = node('wait', 'wait', { durationSec: 60 });
    render(
      <NodeInspector
        node={wait}
        definition={definition([wait])}
        onSave={onSave}
        onRequestDelete={() => {}}
      />
    );

    expect(screen.getByTestId('node-inspector')).toBeTruthy();
    expect(screen.getByTestId('node-inspector-tab-config')).toBeTruthy();

    fireEvent.click(screen.getByTestId('node-inspector-tab-data'));
    await waitFor(() => expect(screen.getByTestId('node-data-flow')).toBeTruthy());

    fireEvent.click(screen.getByTestId('node-inspector-tab-docs'));
    await waitFor(() => expect(screen.getByTestId('node-docs')).toBeTruthy());

    // footer 在 Tabs 外层：切到说明页仍可保存
    fireEvent.click(screen.getByRole('button', { name: /Save/ }));
    await waitFor(() => expect(onSave).toHaveBeenCalled());
    expect(onSave.mock.calls[0][0]).toBe('wait');
  });

  it('disables delete for start/end nodes', () => {
    const start = node('start', 'start');
    render(
      <NodeInspector
        node={start}
        definition={definition([start])}
        onSave={() => {}}
        onRequestDelete={() => {}}
      />
    );
    expect((screen.getByRole('button', { name: /Delete/ }) as HTMLButtonElement).disabled).toBe(true);
  });
});
