import { describe, it, expect } from 'vitest';
import { buildTaskLogWsUrl, buildWorkflowRunWsUrl } from './ws';

describe('buildTaskLogWsUrl', () => {
  const https = { protocol: 'https:', host: 'easyshell.example.com' };
  const http = { protocol: 'http:', host: 'localhost:5173' };

  it('upgrades to wss on https and keeps ws on http', () => {
    expect(buildTaskLogWsUrl('task-1', 'tok', https)).toBe('wss://easyshell.example.com/ws/task/log/task-1?token=tok');
    expect(buildTaskLogWsUrl('task-1', 'tok', http)).toBe('ws://localhost:5173/ws/task/log/task-1?token=tok');
  });

  it('appends the token as an encoded query parameter (browsers cannot set WS headers)', () => {
    const token = 'a b+c/d=e';
    expect(buildTaskLogWsUrl('task-2', token, https)).toBe(
      `wss://easyshell.example.com/ws/task/log/task-2?token=${encodeURIComponent(token)}`
    );
  });

  it('omits the query string entirely when there is no token', () => {
    expect(buildTaskLogWsUrl('task-3', null, https)).toBe('wss://easyshell.example.com/ws/task/log/task-3');
    expect(buildTaskLogWsUrl('task-3', '', https)).toBe('wss://easyshell.example.com/ws/task/log/task-3');
  });
});

describe('buildWorkflowRunWsUrl', () => {
  const https = { protocol: 'https:', host: 'easyshell.example.com' };

  it('targets the workflow run channel and carries the token', () => {
    expect(buildWorkflowRunWsUrl('run-1', 'tok', https)).toBe('wss://easyshell.example.com/ws/workflow/run/run-1?token=tok');
  });

  it('omits the query string when there is no token', () => {
    expect(buildWorkflowRunWsUrl('run-1', null, https)).toBe('wss://easyshell.example.com/ws/workflow/run/run-1');
  });
});
