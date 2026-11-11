import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import HostDetail from './detail';
import * as host from '../../api/host';
import { queryMyHostPermissions } from '../../api/myHostPermissions';

const route = vi.hoisted(() => ({ id: 'a', navigate: vi.fn() }));
vi.mock('react-router-dom', () => ({ useParams: () => ({ agentId: route.id }), useNavigate: () => route.navigate }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock('@ant-design/charts', () => ({ Line: () => null }));
vi.mock('../../api/host', () => ({
  getHostDetail: vi.fn(), getAgentTags: vi.fn(), getAgentJobs: vi.fn(), getAgentMetrics: vi.fn(),
  getHostSoftware: vi.fn(), getDockerContainers: vi.fn(), triggerDetection: vi.fn(), parseDetection: vi.fn(),
}));
vi.mock('../../api/myHostPermissions', () => ({ queryMyHostPermissions: vi.fn() }));

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: (media: string) => ({ matches: false, media, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }) });
  window.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});
beforeEach(() => {
  route.id = 'a';
  route.navigate.mockReset();
  vi.mocked(host.getHostDetail).mockImplementation(async (id) => ({ code: 200, data: { id, hostname: id, status: 1 } }) as never);
  for (const fn of [host.getAgentTags, host.getAgentJobs, host.getAgentMetrics, host.getHostSoftware, host.getDockerContainers]) {
    vi.mocked(fn).mockResolvedValue({ code: 200, data: [] } as never);
  }
  vi.mocked(queryMyHostPermissions).mockReset();
});
afterEach(() => cleanup());

const result = (id: string, terminal: boolean) => ({ code: 200, data: [{ agentId: id, generation: 1, view: true, execute: false, terminal }] }) as never;
const button = () => screen.getByRole('button', { name: /hostDetail\.webTerminal/ }) as HTMLButtonElement;

describe('host terminal permission', () => {
  it('enables only an online host with terminal permission', async () => {
    vi.mocked(queryMyHostPermissions).mockResolvedValue(result('a', true));
    render(<HostDetail />);
    await waitFor(() => expect(button().disabled).toBe(false));
    fireEvent.click(button());
    expect(route.navigate).toHaveBeenCalledWith('/terminal/a');
  });

  it('distinguishes no permission from a failed query', async () => {
    vi.mocked(queryMyHostPermissions).mockResolvedValueOnce(result('a', false));
    const view = render(<HostDetail />);
    expect(await screen.findByText('hostDetail.terminalPermissionDenied')).toBeTruthy();
    expect(button().disabled).toBe(true);
    view.unmount();
    vi.mocked(queryMyHostPermissions).mockRejectedValueOnce({ response: { status: 503 } });
    render(<HostDetail />);
    expect(await screen.findByText('hostDetail.terminalPermissionError')).toBeTruthy();
    expect(button().disabled).toBe(true);
  });

  it('keeps an offline host disabled even with permission', async () => {
    vi.mocked(host.getHostDetail).mockResolvedValue({ code: 200, data: { id: 'a', hostname: 'a', status: 0 } } as never);
    vi.mocked(queryMyHostPermissions).mockResolvedValue(result('a', true));
    render(<HostDetail />);
    expect(await screen.findByText('hostDetail.terminalOffline')).toBeTruthy();
    expect(button().disabled).toBe(true);
  });

  it('ignores a late permission response after changing hosts', async () => {
    let resolveOld!: (value: never) => void;
    vi.mocked(queryMyHostPermissions).mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
      .mockResolvedValueOnce(result('b', false));
    const view = render(<HostDetail />);
    await screen.findByText('hostDetail.terminalPermissionUnknown');
    route.id = 'b';
    view.rerender(<HostDetail />);
    await screen.findByText('hostDetail.terminalPermissionDenied');
    await act(async () => resolveOld(result('a', true)));
    expect(button().disabled).toBe(true);
  });
});
