import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import Dashboard from './index';
import { getDashboardStats } from '../../api/host';

vi.mock('../../api/host', () => ({ getDashboardStats: vi.fn() }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: (media: string) => ({ matches: false, media, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }) });
  window.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});
afterEach(() => cleanup());

const base = {
  totalAgents: 1, onlineAgents: 1, offlineAgents: 0, unstableAgents: 0,
  avgCpuUsage: 0, avgMemUsage: 0, avgDiskUsage: 0, onlineAgentDetails: [],
  highCpuAgents: 0, highMemAgents: 0, highDiskAgents: 0,
};

describe('dashboard availability', () => {
  it('shows unavailable rather than fabricated task and script counts or an empty task table', async () => {
    vi.mocked(getDashboardStats).mockResolvedValue({ code: 200, data: {
      ...base, scriptsAvailable: false, totalScripts: null, tasksAvailable: false,
      totalTasks: null, todayTasks: null, todaySuccessTasks: null, todayFailedTasks: null,
      taskSuccessRate: null, recentTasks: null,
    } } as never);
    render(<Dashboard />);
    const recent = await screen.findByTestId('recent-tasks-card');
    expect(within(recent).getByText('dashboard.statisticsUnavailable')).toBeTruthy();
    expect(within(recent).queryByRole('table')).toBeNull();
    expect(within(screen.getByTestId('stat-card-dashboard.totalTasks')).getByText('dashboard.statisticsUnavailable')).toBeTruthy();
    expect(within(screen.getByTestId('stat-card-dashboard.totalScripts')).getByText('dashboard.statisticsUnavailable')).toBeTruthy();
    expect(screen.getAllByText('dashboard.statisticsUnavailable')).toHaveLength(7);
  });

  it('renders real zeros and an empty table when statistics are available', async () => {
    vi.mocked(getDashboardStats).mockResolvedValue({ code: 200, data: {
      ...base, scriptsAvailable: true, totalScripts: 0, tasksAvailable: true,
      totalTasks: 0, todayTasks: 0, todaySuccessTasks: 0, todayFailedTasks: 0,
      taskSuccessRate: 0, recentTasks: [],
    } } as never);
    render(<Dashboard />);
    const recent = await screen.findByTestId('recent-tasks-card');
    expect(within(recent).getByRole('table')).toBeTruthy();
    expect(within(screen.getByTestId('stat-card-dashboard.totalTasks')).getByText('0')).toBeTruthy();
    expect(within(screen.getByTestId('stat-card-dashboard.totalScripts')).getByText('0')).toBeTruthy();
    expect(screen.getByText('0.0')).toBeTruthy();
    expect(screen.queryByText('dashboard.statisticsUnavailable')).toBeNull();
  });
});
