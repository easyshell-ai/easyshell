import { describe, expect, it, vi, beforeAll, afterEach } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from '../../../i18n/locales/zh-CN.json';
import enUS from '../../../i18n/locales/en-US.json';

vi.mock('../api', () => ({
  getWorkflowDetail: vi.fn(async () => ({
    code: 200,
    data: {
      workflow: { id: 2, name: 'Smoke', status: 'draft', visibility: 'private', runCount: 0, successCount: 0, createdBy: 1, createdAt: '' },
      versions: [],
      draft: {
        id: 1,
        version: 1,
        baseVersion: 0,
        isValid: false,
        createdAt: '',
        definition: {
          schemaVersion: 1,
          defaultOnUpstreamFailure: 'skip',
          onRejection: 'abort',
          variables: { target: { type: 'string', required: true } },
          nodes: [
            { key: 'start', type: 'start', name: 'Start', position: { x: 0, y: 0 }, config: {} },
            { key: 'end', type: 'end', name: 'End', position: { x: 600, y: 0 }, config: { outputs: [] } },
          ],
          edges: [],
        },
      },
    },
  })),
  saveWorkflowDraft: vi.fn(),
  validateWorkflow: vi.fn(async () => ({ code: 200, data: { valid: true, issues: [] } })),
  publishWorkflow: vi.fn(),
  triggerWorkflowRun: vi.fn(),
  issuesOf: (data: { issues?: unknown }) => (Array.isArray(data?.issues) ? data.issues : []),
}));

import WorkflowEditorPage from './index';

i18next.use(initReactI18next).init({
  resources: { 'zh-CN': { translation: zhCN }, 'en-US': { translation: enUS } },
  lng: 'en-US',
  fallbackLng: 'en-US',
  interpolation: { escapeValue: false },
});

beforeAll(() => {
  Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: 1440 });
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
  (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

afterEach(async () => {
  cleanup();
  await new Promise((resolve) => setTimeout(resolve, 0));
});

describe('WorkflowEditorPage smoke', () => {
  it('renders toolbar, palette, canvas, empty state and definition JSON', async () => {
    render(
      <MemoryRouter initialEntries={['/workflow/editor/2']}>
        <Routes>
          <Route path="/workflow/editor/:id" element={<WorkflowEditorPage />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getByTestId('workflow-canvas')).toBeTruthy());
    expect(screen.getByTestId('palette-start')).toBeTruthy();
    expect(screen.getByTestId('palette-script-exec')).toBeTruthy();
    expect(screen.getByTestId('save-draft')).toBeTruthy();
    expect(screen.getByTestId('open-publish')).toBeTruthy();
    expect(screen.getByTestId('open-trigger')).toBeTruthy();
    expect(screen.getByTestId('workflow-help')).toBeTruthy();
    expect(screen.getByTestId('workflow-templates')).toBeTruthy();
    expect(screen.getByTestId('validate-workflow')).toBeTruthy();
    expect(screen.queryByTestId('node-inspector')).toBeNull();
    expect(screen.getByTestId('canvas-empty-state')).toBeTruthy();
    expect(screen.getByText('Definition JSON (read-only)')).toBeTruthy();
  });
});
