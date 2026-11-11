import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Form } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { clearSession } from '../../../../api/request';
import { CodeFields } from './CodeFields';
import { testWorkflowCode } from '../../api';

vi.mock('../../api', () => ({ testWorkflowCode: vi.fn() }));

vi.mock('react-i18next', async (importOriginal) => ({
  ...await importOriginal<typeof import('react-i18next')>(),
  useTranslation: () => ({ t: (key: string) => key }),
}));

vi.mock('@uiw/react-codemirror', () => ({
  default: ({ value, onChange, 'aria-label': label }: {
    value: string; onChange: (value: string) => void; 'aria-label': string;
  }) => <textarea aria-label={label} value={value} onChange={(event) => onChange(event.target.value)} />,
}));

beforeAll(() => {
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
  Object.defineProperty(window, 'matchMedia', { writable: true, value: (media: string) => ({
    matches: false, media, addListener: () => {}, removeListener: () => {},
    addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false,
  }) });
});
afterEach(() => { cleanup(); vi.mocked(testWorkflowCode).mockReset(); });

function renderSample({ id = '7', code = 'function main(input) { return input; }', timeoutSec = 5 } = {}) {
  return render(<MemoryRouter initialEntries={[`/workflow/${id}`]}><Routes><Route path="/workflow/:id" element={
    <Form initialValues={{ codeLanguage: 'javascript', code, codeTimeoutSec: timeoutSec }}>
      <CodeFields groups={[]} inputs={{}} onInputsChange={() => {}} />
    </Form>
  } /></Routes></MemoryRouter>);
}

const runButton = () => screen.getByRole('button', { name: /workflow\.code\.sampleRun/ }) as HTMLButtonElement;
const sampleInput = () => screen.getByLabelText('workflow.code.sampleInput');

describe('CodeFields', () => {
  it.each([
    ['javascript', 'function main(input) {\n  return { result: input };\n}'],
    ['python', 'def main(input):\n    return {"result": input}\n'],
  ])('inserts a %s main skeleton into the form', (language, skeleton) => {
    render(<Form initialValues={{ codeLanguage: language, code: '' }}><CodeFields groups={[]} inputs={{}} onInputsChange={() => {}} /></Form>);
    fireEvent.click(screen.getByRole('button', { name: /workflow\.code\.insertSkeleton/ }));
    expect((screen.getByLabelText('workflow.code.code') as HTMLTextAreaElement).value).toBe(skeleton);
    expect(screen.getByText('workflow.code.unavailableNotice')).toBeTruthy();
    expect(screen.getByText(/workflow\.code\.outputContract/)).toBeTruthy();
  });

  it.each(['[]', 'null', '1', '"text"', '{'])('rejects sample input %s before requesting', (sample) => {
    renderSample();
    fireEvent.change(sampleInput(), { target: { value: sample } });
    expect(runButton().disabled).toBe(true);
    expect(screen.getByText('workflow.code.sampleInvalid')).toBeTruthy();
    fireEvent.click(runButton());
    expect(testWorkflowCode).not.toHaveBeenCalled();
  });

  it.each(['0', '-1', '1.5', 'new', '9007199254740992'])('rejects invalid workflow id %s', (id) => {
    renderSample({ id });
    expect(runButton().disabled).toBe(true);
    fireEvent.click(runButton());
    expect(testWorkflowCode).not.toHaveBeenCalled();
  });

  it('disables the sample for an empty source', () => {
    renderSample({ code: '  ' });
    expect(runButton().disabled).toBe(true);
  });

  it.each([0, 6, 1.5])('validates timeout %s before requesting', (timeoutSec) => {
    renderSample({ timeoutSec });
    fireEvent.click(runButton());
    expect(screen.getByText('workflow.code.sampleTimeoutInvalid')).toBeTruthy();
    expect(testWorkflowCode).not.toHaveBeenCalled();
  });

  it.each([{ result: 42 }, [1, 2], 'value', 42, false, null])('renders JSON output %j', async (data) => {
    vi.mocked(testWorkflowCode).mockResolvedValue({ code: 200, message: '', data });
    renderSample();
    fireEvent.change(sampleInput(), { target: { value: '{"value":42}' } });
    fireEvent.click(runButton());
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe(JSON.stringify(data, null, 2)));
    expect(testWorkflowCode).toHaveBeenCalledWith(7, {
      language: 'javascript', code: 'function main(input) { return input; }', timeoutSec: 5, input: { value: 42 },
    });
    expect(screen.getByText('workflow.code.unavailableNotice')).toBeTruthy();
  });

  it('allows only one request while loading', async () => {
    let resolve!: (value: Awaited<ReturnType<typeof testWorkflowCode>>) => void;
    vi.mocked(testWorkflowCode).mockReturnValue(new Promise((done) => { resolve = done; }));
    renderSample();
    fireEvent.click(runButton());
    fireEvent.click(runButton());
    expect(testWorkflowCode).toHaveBeenCalledTimes(1);
    expect(runButton().disabled).toBe(true);
    await act(async () => resolve({ code: 200, message: '', data: true }));
    expect(runButton().disabled).toBe(false);
  });

  it.each([400, 503])('clears previous output and shows a safe error for response %s', async (code) => {
    vi.mocked(testWorkflowCode).mockResolvedValueOnce({ code: 200, message: '', data: 'old' })
      .mockResolvedValueOnce({ code, message: 'secret source', data: 'unsafe' });
    renderSample();
    fireEvent.click(runButton());
    await screen.findByRole('status');
    await waitFor(() => expect(runButton().classList.contains('ant-btn-loading')).toBe(false));
    fireEvent.click(runButton());
    await screen.findByText(code === 503 ? 'workflow.code.sampleServiceUnavailable' : 'workflow.code.sampleFailed');
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByText(/secret source|unsafe/)).toBeNull();
  });

  it.each([503, 500])('handles HTTP error %s without rendering request details', async (status) => {
    vi.mocked(testWorkflowCode).mockRejectedValue({ response: { status }, message: 'secret source', config: { data: 'secret input' } });
    renderSample();
    fireEvent.click(runButton());
    await screen.findByText(status === 503 ? 'workflow.code.sampleServiceUnavailable' : 'workflow.code.sampleError');
    expect(screen.queryByText(/secret/)).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
  });

  it.each(['code', 'language', 'sample', 'session', 'unmount'])('ignores pending output after %s changes', async (change) => {
    let resolve!: (value: Awaited<ReturnType<typeof testWorkflowCode>>) => void;
    vi.mocked(testWorkflowCode).mockReturnValue(new Promise((done) => { resolve = done; }));
    const view = renderSample();
    fireEvent.click(runButton());
    if (change === 'code') fireEvent.change(screen.getByLabelText('workflow.code.code'), { target: { value: 'changed' } });
    if (change === 'language') {
      fireEvent.mouseDown(screen.getByLabelText('workflow.code.language'));
      fireEvent.click(await screen.findByText('workflow.code.languagePython'));
    }
    if (change === 'sample') fireEvent.change(sampleInput(), { target: { value: '{"changed":true}' } });
    if (change === 'session') act(() => clearSession());
    if (change === 'unmount') view.unmount();
    await act(async () => resolve({ code: 200, message: '', data: 'stale' }));
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByText('stale')).toBeNull();
  });

  it('keeps the newer result when an invalidated request finishes last', async () => {
    let resolveOld!: (value: Awaited<ReturnType<typeof testWorkflowCode>>) => void;
    vi.mocked(testWorkflowCode).mockReturnValueOnce(new Promise((done) => { resolveOld = done; }))
      .mockResolvedValueOnce({ code: 200, message: '', data: 'new result' });
    renderSample();
    fireEvent.click(runButton());
    fireEvent.change(sampleInput(), { target: { value: '{"new":true}' } });
    fireEvent.click(runButton());
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('"new result"'));
    await act(async () => resolveOld({ code: 200, message: '', data: 'old result' }));
    expect(screen.getByRole('status').textContent).toBe('"new result"');
  });
});
