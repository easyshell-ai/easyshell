import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { Form } from 'antd';
import { LlmFields, StructuredSchemaEditor } from './LlmFields';
import { editableSchema } from './editableSchema';
import { AiAnalyzeFields } from './AiAnalyzeFields';

vi.mock('react-i18next', async (importOriginal) => ({
  ...await importOriginal<typeof import('react-i18next')>(),
  useTranslation: () => ({ t: (key: string) => key }),
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

describe('AI configuration warnings', () => {
  it.each([
    ['LLM', LlmFields],
    ['AI analyze', AiAnalyzeFields],
  ])('shows external input and saved strict protection warnings for %s', (_name, Fields) => {
    render(<Form><Fields groups={[]} /></Form>);
    expect(screen.getByText('workflow.ai.externalInputWarning')).toBeTruthy();
    expect(screen.getByText('workflow.structured.savedProtection')).toBeTruthy();
  });
});

describe('structured schema table round-trip boundary', () => {
  it('accepts basic fields and preserves required and additionalProperties', () => {
    expect(editableSchema('{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}'))
      .toEqual({ properties: { ok: { type: 'boolean' } }, required: ['ok'], additionalProperties: false });
  });
  it('refuses complex or invalid raw schemas instead of replacing them', () => {
    for (const raw of ['{', '{"type":"object","properties":{"v":{"type":"string","enum":["x"]}}}', '{"type":"object","properties":{"x":{"type":"array","items":{"type":"string"}}}}', '{"type":"object","properties":{},"$ref":"x"}']) {
      expect(editableSchema(raw)).toBeNull();
    }
  });
  it('keeps legacy schemas that permit extra fields out of the visual editor', () => {
    expect(editableSchema('{"type":"object","properties":{},"additionalProperties":true}')).toBeNull();
    expect(editableSchema('{"type":"object","properties":{}}')).toBeNull();
  });
  it('creates strict schemas and disables extra properties', () => {
    const onChange = vi.fn();
    render(<StructuredSchemaEditor onChange={onChange} />);
    const checkbox = screen.getByRole('checkbox', { name: 'workflow.structured.additionalProperties' }) as HTMLInputElement;
    expect(checkbox.disabled).toBe(true);
    expect(checkbox.checked).toBe(false);
    fireEvent.click(checkbox);
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('workflow.structured.fieldName'), { target: { value: 'result' } });
    fireEvent.click(screen.getByRole('button', { name: 'workflow.structured.addField' }));
    expect(JSON.parse(onChange.mock.calls[0][0])).toEqual({
      type: 'object', properties: { result: { type: 'string' } }, required: [], additionalProperties: false,
    });
  });
  it.each([
    '{"type":"object","properties":{},"additionalProperties":true}',
    '{"type":"object","properties":{}}',
  ])('preserves legacy raw schema without emitting changes: %s', (raw) => {
    const onChange = vi.fn();
    render(<StructuredSchemaEditor value={raw} onChange={onChange} />);
    expect((screen.getByLabelText('workflow.structured.rawSchema') as HTMLTextAreaElement).value).toBe(raw);
    expect(screen.getByText('workflow.structured.rawOnly')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'workflow.structured.addField' })).toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });
});
