import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import HostPermissions from './HostPermissions';
import { getHostAclCatalog, getHostPermissions, grantHostPermission, revokeHostPermission } from '../../api/hostPermissions';
import { getUserList } from '../../api/system';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock('../../api/hostPermissions', () => ({ getHostAclCatalog: vi.fn(), getHostPermissions: vi.fn(), grantHostPermission: vi.fn(), revokeHostPermission: vi.fn() }));
vi.mock('../../api/system', () => ({ getUserList: vi.fn() }));
beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent() { return false; } }) });
  window.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});
afterEach(() => { cleanup(); vi.resetAllMocks(); });
const ok = <T,>(data: T) => ({ code: 200, message: '', data });
const host = { agentId: 'h1', hostname: 'Alpha', generation: 7 };
const grant = { subjectType: 'USER' as const, subjectId: '1', permission: 'VIEW' as const };
function setup() {
  vi.mocked(getHostAclCatalog).mockResolvedValue(ok([host]));
  vi.mocked(getUserList).mockResolvedValue(ok([{ id: 1, username: 'Alice', email: '', role: 'viewer', status: 1, lastLoginAt: '', createdAt: '' }]));
  vi.mocked(getHostPermissions).mockResolvedValue(ok([grant]));
}
async function select(label: string, option: string) {
  await waitFor(() => expect(screen.getByLabelText(label).closest('.ant-select')?.className).not.toContain('ant-select-disabled'));
  fireEvent.mouseDown(screen.getByLabelText(label));
  fireEvent.click((await screen.findAllByText(option)).find((el) => el.classList.contains('ant-select-item-option-content'))!);
}
describe('HostPermissions', () => {
  it('shows the independent permissions notice', () => {
    setup(); render(<HostPermissions />);
    expect(screen.getByText('hostPermissions.independentPermissionsNotice')).toBeTruthy();
  });
  it('loads and revokes an explicit grant with generation', async () => {
    setup(); vi.mocked(revokeHostPermission).mockResolvedValue(ok(undefined));
    render(<HostPermissions />); await select('hostPermissions.host', 'Alpha (h1)');
    expect(await screen.findByText('Alice (1)')).toBeTruthy();
    fireEvent.click(screen.getByText('hostPermissions.revoke'));
    await waitFor(() => expect(revokeHostPermission).toHaveBeenCalledWith('h1', { ...grant, expectedGeneration: 7 }));
    await waitFor(() => expect(getHostPermissions).toHaveBeenCalledTimes(2));
  });
  it('grants a selected role with generation', async () => {
    setup(); vi.mocked(grantHostPermission).mockResolvedValue(ok(undefined));
    render(<HostPermissions />); await select('hostPermissions.host', 'Alpha (h1)');
    await select('hostPermissions.principalType', 'hostPermissions.role');
    await select('hostPermissions.principal', 'operator (operator)');
    fireEvent.click(screen.getByText('hostPermissions.grant'));
    await waitFor(() => expect(grantHostPermission).toHaveBeenCalledWith('h1', { subjectType: 'ROLE', subjectId: 'operator', permission: 'VIEW', expectedGeneration: 7 }));
  });
  it('blocks terminal for a viewer role even when terminal was selected first', async () => {
    setup(); render(<HostPermissions />); await select('hostPermissions.host', 'Alpha (h1)');
    await select('hostPermissions.permission', 'hostPermissions.terminal');
    await select('hostPermissions.principalType', 'hostPermissions.role');
    await select('hostPermissions.principal', 'viewer (viewer)');
    expect(screen.getByText('hostPermissions.viewerTerminalForbidden')).toBeTruthy();
    expect((screen.getByText('hostPermissions.grant').closest('button') as HTMLButtonElement).disabled).toBe(true);
    expect(grantHostPermission).not.toHaveBeenCalled();
    await select('hostPermissions.principal', 'operator (operator)');
    expect(screen.queryByText('hostPermissions.viewerTerminalForbidden')).toBeNull();
    expect((screen.getByText('hostPermissions.grant').closest('button') as HTMLButtonElement).disabled).toBe(false);
  });
  it('disables terminal for a user whose catalog role is viewer without blocking view', async () => {
    setup(); render(<HostPermissions />); await select('hostPermissions.host', 'Alpha (h1)');
    await select('hostPermissions.principal', 'Alice (1)');
    expect(screen.getByText('hostPermissions.viewerTerminalForbidden')).toBeTruthy();
    expect((screen.getByText('hostPermissions.grant').closest('button') as HTMLButtonElement).disabled).toBe(false);
    fireEvent.mouseDown(screen.getByLabelText('hostPermissions.permission'));
    const option = (await screen.findAllByText('hostPermissions.terminal')).find((el) => el.classList.contains('ant-select-item-option-content'))!;
    expect(option.closest('.ant-select-item-option')?.className).toContain('ant-select-item-option-disabled');
  });
  it('reports server rejection for terminal when a user role is unavailable', async () => {
    setup();
    vi.mocked(getUserList).mockResolvedValue(ok([{ id: 1, username: 'Alice', email: '', role: undefined as unknown as string, status: 1, lastLoginAt: '', createdAt: '' }]));
    vi.mocked(grantHostPermission).mockResolvedValue({ code: 400, message: '', data: undefined });
    render(<HostPermissions />); await select('hostPermissions.host', 'Alpha (h1)');
    await select('hostPermissions.principal', 'Alice (1)');
    await select('hostPermissions.permission', 'hostPermissions.terminal');
    fireEvent.click(screen.getByText('hostPermissions.grant'));
    expect(await screen.findByText('hostPermissions.invalidGrant')).toBeTruthy();
    expect(grantHostPermission).toHaveBeenCalledWith('h1', { subjectType: 'USER', subjectId: '1', permission: 'TERMINAL', expectedGeneration: 7 });
  });
  it('does not show a fake empty table on denied catalog', async () => {
    setup(); vi.mocked(getHostAclCatalog).mockResolvedValue({ code: 403, message: '', data: null as never });
    render(<HostPermissions />); expect(await screen.findByText('hostPermissions.forbidden')).toBeTruthy();
    expect(document.querySelector('.ant-table')).toBeNull();
  });
  it('does not show a fake empty table on denied grant list', async () => {
    setup(); vi.mocked(getHostPermissions).mockResolvedValue({ code: 403, message: '', data: null as never });
    render(<HostPermissions />); await select('hostPermissions.host', 'Alpha (h1)');
    expect(await screen.findByText('hostPermissions.forbidden')).toBeTruthy();
    expect(document.querySelector('.ant-table')).toBeNull();
  });
  it('reports failed revoke and retains the displayed grant', async () => {
    setup(); vi.mocked(revokeHostPermission).mockRejectedValue(new Error('network'));
    render(<HostPermissions />); await select('hostPermissions.host', 'Alpha (h1)');
    fireEvent.click(await screen.findByText('hostPermissions.revoke'));
    expect(await screen.findByText('hostPermissions.saveError')).toBeTruthy();
    expect(screen.getByText('Alice (1)')).toBeTruthy(); expect(getHostPermissions).toHaveBeenCalledTimes(1);
  });
  it('ignores obsolete responses after switching hosts', async () => {
    setup(); vi.mocked(getHostAclCatalog).mockResolvedValue(ok([host, { agentId: 'h2', hostname: 'Beta', generation: 2 }]));
    let resolve!: (value: ReturnType<typeof ok<typeof grant[]>>) => void;
    vi.mocked(getHostPermissions).mockImplementationOnce(() => new Promise((r) => { resolve = r; })).mockResolvedValueOnce(ok([]));
    render(<HostPermissions />); await select('hostPermissions.host', 'Alpha (h1)');
    await select('hostPermissions.host', 'Beta (h2)');
    await act(async () => resolve(ok([grant])));
    await waitFor(() => expect(getHostPermissions).toHaveBeenCalledWith('h2'));
    expect(screen.queryByText('Alice (1)')).toBeNull();
  });
  it('hides and disables the old host grants while the new host is loading', async () => {
    setup();
    vi.mocked(getHostAclCatalog).mockResolvedValue(ok([host, { agentId: 'h2', hostname: 'Beta', generation: 2 }]));
    let resolveSecond!: (value: ReturnType<typeof ok<typeof grant[]>>) => void;
    vi.mocked(getHostPermissions).mockResolvedValueOnce(ok([grant]))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveSecond = resolve; }));
    render(<HostPermissions />);
    await select('hostPermissions.host', 'Alpha (h1)');
    expect(await screen.findByText('hostPermissions.revoke')).toBeTruthy();
    await select('hostPermissions.host', 'Beta (h2)');
    expect(screen.queryByText('Alice (1)')).toBeNull();
    expect(screen.queryByText('hostPermissions.revoke')).toBeNull();
    expect((screen.getByText('hostPermissions.grant').closest('button') as HTMLButtonElement).disabled).toBe(true);
    expect(revokeHostPermission).not.toHaveBeenCalled();
    await act(async () => resolveSecond(ok([])));
    await waitFor(() => expect(document.querySelector('.ant-table')).not.toBeNull());
    expect(screen.queryByText('hostPermissions.revoke')).toBeNull();
  });
  it('detects changed generation during list loading', async () => {
    setup(); vi.mocked(getHostAclCatalog).mockResolvedValueOnce(ok([host])).mockResolvedValue(ok([{ ...host, generation: 8 }]));
    render(<HostPermissions />); await select('hostPermissions.host', 'Alpha (h1)');
    expect(await screen.findByText('hostPermissions.stale')).toBeTruthy(); expect(document.querySelector('.ant-table')).toBeNull();
  });
  it('requires refresh on mutation conflict', async () => {
    setup(); vi.mocked(revokeHostPermission).mockResolvedValue({ code: 409, message: '', data: undefined });
    render(<HostPermissions />); await select('hostPermissions.host', 'Alpha (h1)');
    fireEvent.click(await screen.findByText('hostPermissions.revoke'));
    expect(await screen.findByText('hostPermissions.stale')).toBeTruthy(); expect(document.querySelector('.ant-table')).toBeNull();
  });
  it('reports rejected grant without reloading', async () => {
    setup(); vi.mocked(grantHostPermission).mockResolvedValue({ code: 403, message: '', data: undefined });
    render(<HostPermissions />); await select('hostPermissions.host', 'Alpha (h1)');
    await select('hostPermissions.principal', 'Alice (1)'); fireEvent.click(screen.getByText('hostPermissions.grant'));
    expect(await screen.findByText('hostPermissions.forbidden')).toBeTruthy(); expect(getHostPermissions).toHaveBeenCalledTimes(1);
  });
  it('loads all catalog pages', async () => {
    setup(); const first = Array.from({ length: 100 }, (_, i) => ({ agentId: `host${i}`, hostname: `Host${i}`, generation: 1 }));
    vi.mocked(getHostAclCatalog).mockImplementation(async (page) => ok(page === 0 ? first : [host]));
    render(<HostPermissions />); await waitFor(() => expect(getHostAclCatalog).toHaveBeenCalledWith(1, 100));
    fireEvent.mouseDown(screen.getByLabelText('hostPermissions.host'));
    fireEvent.change(screen.getByLabelText('hostPermissions.host'), { target: { value: 'Alpha' } });
    fireEvent.click(await screen.findByText('Alpha (h1)'));
    expect(getHostAclCatalog).toHaveBeenCalledWith(1, 100);
  });
  it('fails closed if pagination repeats a host', async () => {
    setup();
    const first = Array.from({ length: 100 }, (_, i) => ({ agentId: `host${i}`, hostname: `Host${i}`, generation: 1 }));
    vi.mocked(getHostAclCatalog).mockImplementation(async (page) => ok(page === 0 ? first : [first[0]]));
    render(<HostPermissions />);
    expect(await screen.findByText('hostPermissions.stale')).toBeTruthy();
    expect(document.querySelector('.ant-table')).toBeNull();
  });
});
