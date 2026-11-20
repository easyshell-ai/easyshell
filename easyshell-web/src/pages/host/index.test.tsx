import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import Host from './index';
import { getHostList } from '../../api/host';
import { getMe } from '../../api/auth';
import { getProvisionList } from '../../api/provision';

vi.mock('react-router-dom', () => ({ useNavigate: () => vi.fn() }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock('../../api/host', () => ({ getHostList: vi.fn(), getAgentTags: vi.fn(), deleteHost: vi.fn(), deleteCredential: vi.fn() }));
vi.mock('../../api/auth', () => ({ getMe: vi.fn() }));
vi.mock('../../api/provision', () => ({ getProvisionList: vi.fn(), getProvisionById: vi.fn(), provisionHost: vi.fn(), deleteProvision: vi.fn(), retryProvision: vi.fn(), batchDeploy: vi.fn(), importCsv: vi.fn(), downloadTemplate: vi.fn(), reinstallByCredentialId: vi.fn() }));
vi.mock('../../api/system', () => ({ getSystemConfigList: vi.fn().mockResolvedValue({ code: 200, data: [{ configKey: 'server.external-url', configValue: 'url' }] }) }));
vi.mock('../../hooks/useVersion', () => ({ useVersion: () => undefined }));
Object.defineProperty(window, 'matchMedia', { writable: true, value: () => ({ matches: false, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn() }) });
vi.mock('@ant-design/pro-components', async () => {
  const React = await import('react');
  return { ProTable: ({ request, toolBarRender }: { request: (params: object) => Promise<{ data: { agentId: string }[]; total: number }>; toolBarRender: () => React.ReactNode }) => {
    const [result, setResult] = React.useState<{ data: { agentId: string }[]; total: number }>();
    React.useEffect(() => { request({ current: 2, pageSize: 2 }).then(setResult); }, [request]);
    return <div>{result && `${result.total}:${result.data.map((row) => row.agentId).join(',')}`}{toolBarRender()}</div>;
  } };
});

describe('deployment history', () => {
  it('clears stale history on failure and retries to a genuinely empty result', async () => {
    vi.mocked(getMe).mockResolvedValue({ code: 200, data: { role: 'admin' } } as never);
    vi.mocked(getHostList).mockResolvedValue({ code: 200, data: [] } as never);
    vi.mocked(getProvisionList).mockReset().mockResolvedValueOnce({ code: 200, data: [{ id: 1, ip: '192.0.2.1', sshPort: 22, sshUsername: 'root', provisionStatus: 'SUCCESS' }] } as never)
      .mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ code: 200, data: [] } as never);
    render(<Host />);
    fireEvent.click(await screen.findByRole('button', { name: /host.deployHistory/ }));
    expect(await screen.findByText('192.0.2.1')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /common.refresh/ }));
    expect(await screen.findByText('host.loadHistoryFailed')).toBeTruthy();
    expect(screen.queryByText('192.0.2.1')).toBeNull();
    expect(screen.queryByText('host.noDeployRecords')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /common.retry/ }));
    expect(await screen.findByText('host.noDeployRecords')).toBeTruthy();
    expect(screen.getByText('host.credentialOwnershipUnknown')).toBeTruthy();
  });

  it('ignores an older response after a newer request succeeds', async () => {
    vi.mocked(getMe).mockResolvedValue({ code: 200, data: { role: 'admin' } } as never);
    vi.mocked(getHostList).mockResolvedValue({ code: 200, data: [] } as never);
    let resolveOld!: (value: never) => void;
    vi.mocked(getProvisionList).mockReset().mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
      .mockResolvedValueOnce({ code: 200, data: [] } as never);
    render(<Host />);
    fireEvent.click(await screen.findByRole('button', { name: /host.deployHistory/ }));
    expect(screen.queryByText('host.noDeployRecords')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /common.refresh/ }));
    expect(await screen.findByText('host.noDeployRecords')).toBeTruthy();
    await act(async () => resolveOld({ code: 200, data: [{ id: 2, ip: '192.0.2.2', provisionStatus: 'SUCCESS' }] } as never));
    expect(screen.queryByText('192.0.2.2')).toBeNull();
    expect(screen.getByText('host.noDeployRecords')).toBeTruthy();
  });
});

afterEach(() => cleanup());

describe('host list', () => {
  it('loads authorized agents independently of credentials and slices the requested page', async () => {
    vi.mocked(getMe).mockResolvedValue({ code: 200, data: { role: 'viewer' } } as never);
    vi.mocked(getHostList).mockResolvedValue({ code: 200, data: Array.from({ length: 5 }, (_, i) => ({ id: `agent-${i}` })) } as never);
    const { getAgentTags } = await import('../../api/host');
    vi.mocked(getAgentTags).mockResolvedValue({ code: 200, data: [] } as never);
    render(<Host />);
    await waitFor(() => expect(screen.getByText('5:agent-2,agent-3')).toBeTruthy());
    expect(screen.getByText('host.credentialListUnavailable')).toBeTruthy();
  });
});
