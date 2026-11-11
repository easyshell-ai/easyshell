import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, renderHook } from '@testing-library/react';
import { useAiChat } from './useAiChat';

const mocks = vi.hoisted(() => ({
  epoch: 0,
  listeners: new Set<() => void>(),
  send: vi.fn(), messages: vi.fn(), save: vi.fn(), sessions: vi.fn(), deleteSession: vi.fn(),
  approve: vi.fn(), reject: vi.fn(), confirmPlan: vi.fn(), rejectPlan: vi.fn(), approveCheckpoint: vi.fn(), rejectCheckpoint: vi.fn(), warning: vi.fn(),
}));
vi.mock('../api/request', () => ({
  getSessionEpoch: () => mocks.epoch,
  subscribeSessionEpoch: (listener: () => void) => {
    mocks.listeners.add(listener);
    return () => { mocks.listeners.delete(listener); };
  },
}));
vi.mock('../api/ai', () => ({
  getChatSessions: mocks.sessions,
  getChatMessages: mocks.messages, saveProcessData: mocks.save, sendChatStream: mocks.send,
  deleteChatSession: mocks.deleteSession, approveExecution: mocks.approve, rejectExecution: mocks.reject,
  confirmPlan: mocks.confirmPlan, rejectPlan: mocks.rejectPlan, approveCheckpoint: mocks.approveCheckpoint, rejectCheckpoint: mocks.rejectCheckpoint,
}));
vi.mock('../api/cluster', () => ({ getClusterList: vi.fn().mockResolvedValue({ code: 200, data: [] }), getClusterDetail: vi.fn() }));
vi.mock('../api/host', () => ({ getHostList: vi.fn().mockResolvedValue({ code: 200, data: [] }) }));
vi.mock('antd', () => ({ Input: { TextArea: () => null }, theme: { useToken: () => ({ token: {} }) }, message: { error: vi.fn(), success: vi.fn(), warning: mocks.warning } }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));

function changeEpoch() {
  mocks.epoch++;
  mocks.listeners.forEach(listener => listener());
}

beforeEach(() => {
  mocks.send.mockReset();
  mocks.warning.mockReset();
  mocks.sessions.mockReset().mockResolvedValue({ code: 200, data: [] });
  mocks.deleteSession.mockReset().mockResolvedValue({ code: 200 });
  mocks.messages.mockReset().mockResolvedValue({ code: 200, data: [] });
  mocks.save.mockReset().mockResolvedValue({ code: 200 });
  for (const mock of [mocks.approve, mocks.reject, mocks.confirmPlan, mocks.rejectPlan, mocks.approveCheckpoint, mocks.rejectCheckpoint]) mock.mockReset().mockResolvedValue({ code: 200 });
});

describe('decision isolation', () => {
  it.each([
    ['approval', 'approve', 'handleApprove', 'approved'],
    ['approval', 'reject', 'handleReject', 'rejected'],
    ['checkpoint', 'approveCheckpoint', 'handleApproveCheckpoint', ''],
    ['checkpoint', 'rejectCheckpoint', 'handleRejectCheckpoint', ''],
    ['plan', 'confirmPlan', 'handleConfirmPlan', 'confirmed'],
    ['plan', 'rejectPlan', 'handleRejectPlan', 'rejected'],
  ] as const)('preserves %s %s result across view switches and later events', async (kind, mockName, handler, status) => {
    let resolve!: (value: unknown) => void;
    mocks[mockName].mockReturnValue(new Promise(res => { resolve = res; }));
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSuggestedPrompt('hello'));
    const event = mocks.send.mock.calls[0][1];
    act(() => {
      event({ type: 'SESSION', sessionId: 'a' });
      event({ type: 'APPROVAL', approvalData: { taskId: 'task' } });
      event({ type: 'STEP_CHECKPOINT', stepIndex: 3 });
      event({ type: 'PLAN_AWAIT_CONFIRMATION', planId: 'plan' });
    });
    act(() => { if (kind === 'approval') (hook.result.current[handler] as (id: string) => void)('task'); else if (kind === 'checkpoint') (hook.result.current[handler] as (id: number) => void)(3); else (hook.result.current[handler] as () => void)(); });
    act(() => hook.result.current.handleSelectSession('b'));
    await act(async () => resolve({ code: 200 }));
    expect(hook.result.current.currentSessionId).toBe('b');
    expect(hook.result.current.pendingApprovals).toEqual([]);
    act(() => hook.result.current.handleSelectSession('a'));
    act(() => event({ type: 'CONTENT', content: 'later' }));
    if (kind === 'approval') expect(hook.result.current.pendingApprovals[0].status).toBe(status);
    if (kind === 'checkpoint') expect(hook.result.current.checkpointSteps.has(3)).toBe(false);
    if (kind === 'plan') expect(hook.result.current.planConfirmationStatus).toBe(status);
  });

  it('clears all actionable state on stop and ignores late decisions after epoch change', async () => {
    let resolve!: (value: unknown) => void;
    mocks.approve.mockReturnValue(new Promise(res => { resolve = res; }));
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSuggestedPrompt('hello'));
    const event = mocks.send.mock.calls[0][1];
    act(() => { event({ type: 'SESSION', sessionId: 'a' }); event({ type: 'APPROVAL', approvalData: { taskId: 'task' } }); event({ type: 'STEP_CHECKPOINT', stepIndex: 3 }); event({ type: 'PLAN_AWAIT_CONFIRMATION', planId: 'plan' }); });
    act(() => { hook.result.current.handleApprove('task'); hook.result.current.handleStop(); });
    expect(hook.result.current.pendingApprovals).toEqual([]);
    expect(hook.result.current.checkpointSteps.size).toBe(0);
    expect(hook.result.current.planConfirmationStatus).toBeUndefined();
    expect(hook.result.current.approvalLoading).toBe(false);
    act(changeEpoch);
    await act(async () => resolve({ code: 200 }));
    expect(hook.result.current.pendingApprovals).toEqual([]);
  });
});
afterEach(() => { cleanup(); act(changeEpoch); });

describe('chat authentication epoch isolation', () => {
  it('rejects old draft and prompt callbacks for a new chat, including before rerender', async () => {
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.setInputValue('private draft'));
    const oldSend = hook.result.current.handleSend;
    const oldPrompt = hook.result.current.handleSuggestedPrompt;
    act(() => {
      changeEpoch();
      oldSend();
      oldPrompt('private suggestion');
    });
    expect(mocks.send).not.toHaveBeenCalled();
    expect(hook.result.current.messages).toEqual([]);
    expect(hook.result.current.inputValue).toBe('');
    expect(hook.result.current.loading).toBe(false);
    act(() => hook.result.current.handleSuggestedPrompt('fresh'));
    expect(mocks.send).toHaveBeenCalledOnce();
    expect(mocks.send.mock.calls[0][0].message).toBe('fresh');
    await act(async () => {});
  });

  it('rejects callbacks from A after A → B → A and preserves the new draft', async () => {
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => { hook.result.current.handleSelectSession('a'); hook.result.current.setInputValue('old A draft'); });
    const oldSend = hook.result.current.handleSend;
    const oldPrompt = hook.result.current.handleSuggestedPrompt;
    act(changeEpoch);
    act(() => hook.result.current.handleSelectSession('b'));
    act(changeEpoch);
    act(() => { hook.result.current.handleSelectSession('a'); hook.result.current.setInputValue('new A draft'); });
    await act(async () => { await Promise.resolve(); oldSend(); oldPrompt('old A prompt'); });
    expect(mocks.send).not.toHaveBeenCalled();
    expect(hook.result.current.inputValue).toBe('new A draft');
    expect(hook.result.current.messages).toEqual([]);
    act(() => hook.result.current.handleSend());
    expect(mocks.send.mock.calls[0][0]).toMatchObject({ sessionId: 'a', message: 'new A draft' });
  });

  it('does not let a stale retry mutate new-epoch messages', async () => {
    mocks.messages.mockResolvedValue({ code: 200, data: [
      { id: 1, role: 'user', content: 'old question' },
      { id: 2, role: 'assistant', content: 'old answer' },
    ] });
    const hook = renderHook(() => useAiChat());
    await act(async () => { hook.result.current.handleSelectSession('a'); });
    const oldRetry = hook.result.current.handleRetry;
    act(changeEpoch);
    mocks.messages.mockResolvedValue({ code: 200, data: [
      { id: 3, role: 'user', content: 'new question' },
      { id: 4, role: 'assistant', content: 'new answer' },
    ] });
    await act(async () => { hook.result.current.handleSelectSession('a'); });
    await act(async () => { await Promise.resolve(); oldRetry(); });
    expect(mocks.send).not.toHaveBeenCalled();
    expect(hook.result.current.messages.map(m => m.content)).toEqual(['new question', 'new answer']);
  });

  it('clears drafts and process selection when credentials change', async () => {
    const hook = renderHook(() => useAiChat());
    act(() => {
      hook.result.current.setInputValue('private draft');
      hook.result.current.setProcessModalVisible(true);
      hook.result.current.setViewingProcessMsgId(42);
      hook.result.current.setHoveredMsgId(42);
    });
    act(changeEpoch);
    expect(hook.result.current.inputValue).toBe('');
    expect(hook.result.current.processModalVisible).toBe(false);
    expect(hook.result.current.viewingProcessMsgId).toBeNull();
    expect(hook.result.current.hoveredMsgId).toBeNull();
    await act(async () => {});
  });
  it('aborts and discards an unmounted stream immediately on session change', async () => {
    const controller = new AbortController();
    mocks.send.mockReturnValue(controller);
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSuggestedPrompt('private'));
    const event = mocks.send.mock.calls[0][1];
    act(() => event({ type: 'CONTENT', content: 'secret' }));
    hook.unmount();
    act(changeEpoch);
    expect(controller.signal.aborted).toBe(true);
    const next = renderHook(() => useAiChat());
    expect(next.result.current.streamingContent).toBe('');
    expect(next.result.current.messages).toEqual([]);
    await act(async () => {});
  });

  it.each(['resolve', 'reject'] as const)('does not finalize with new credentials when save %s arrives late', async (outcome) => {
    let resolve!: (value: unknown) => void;
    let reject!: (error: Error) => void;
    mocks.save.mockReturnValue(new Promise((res, rej) => { resolve = res; reject = rej; }));
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSuggestedPrompt('private'));
    const [, event, , complete] = mocks.send.mock.calls[0];
    act(() => { event({ type: 'SESSION', sessionId: 'old-id' }); event({ type: 'THINKING', content: 'private' }); complete(); });
    expect(mocks.save).toHaveBeenCalledOnce();
    act(changeEpoch);
    await act(async () => { if (outcome === 'resolve') resolve({ code: 200 }); else reject(new Error('late')); });
    expect(mocks.messages).not.toHaveBeenCalled();
    expect(hook.result.current.messages).toEqual([]);
  });

  it('clears a completed stream cached while the hook is unmounted', async () => {
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSuggestedPrompt('private'));
    const [, event, , complete] = mocks.send.mock.calls[0];
    act(() => { event({ type: 'SESSION', sessionId: 'old-id' }); event({ type: 'CONTENT', content: 'secret' }); });
    hook.unmount();
    act(() => complete());
    act(changeEpoch);
    const next = renderHook(() => useAiChat());
    expect(next.result.current.currentSessionId).toBeNull();
    expect(next.result.current.messages).toEqual([]);
    await act(async () => {});
  });

  it('ignores a message response from the old epoch', async () => {
    let resolve!: (value: unknown) => void;
    mocks.messages.mockReturnValue(new Promise(res => { resolve = res; }));
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSelectSession('old-id'));
    act(changeEpoch);
    await act(async () => resolve({ code: 200, data: [{ id: 1, content: 'secret' }] }));
    expect(hook.result.current.messages).toEqual([]);
  });
});

describe('chat stream view lifecycle', () => {
  it('rejects callbacks from a new chat after selecting a session, even before rerender', async () => {
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.setInputValue('old draft'));
    const { handleSend, handleSuggestedPrompt } = hook.result.current;
    act(() => {
      hook.result.current.handleSelectSession('a');
      handleSend();
      handleSuggestedPrompt('old prompt');
    });
    expect(mocks.send).not.toHaveBeenCalled();
    expect(hook.result.current.inputValue).toBe('old draft');
    await act(async () => {});
  });

  it('rejects same-epoch A → B → A callbacks including retry', async () => {
    mocks.send.mockReturnValue(new AbortController());
    mocks.messages.mockResolvedValue({ code: 200, data: [
      { id: 1, role: 'user', content: 'question' },
      { id: 2, role: 'assistant', content: 'answer' },
    ] });
    const hook = renderHook(() => useAiChat());
    await act(async () => hook.result.current.handleSelectSession('a'));
    act(() => hook.result.current.setInputValue('draft'));
    const { handleSend, handleSuggestedPrompt, handleRetry } = hook.result.current;
    await act(async () => hook.result.current.handleSelectSession('b'));
    await act(async () => hook.result.current.handleSelectSession('a'));
    act(() => { handleSend(); handleSuggestedPrompt('old'); handleRetry(); });
    expect(mocks.send).not.toHaveBeenCalled();
    expect(hook.result.current.messages.map(m => m.content)).toEqual(['question', 'answer']);
    expect(hook.result.current.inputValue).toBe('draft');
  });

  it('rejects callbacks after unmount', async () => {
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.setInputValue('draft'));
    const { handleSend, handleSuggestedPrompt, handleRetry } = hook.result.current;
    hook.unmount();
    act(() => { handleSend(); handleSuggestedPrompt('old'); handleRetry(); });
    expect(mocks.send).not.toHaveBeenCalled();
    await act(async () => {});
  });

  it('keeps messages unchanged when retry is blocked and reuses the last user message when allowed', async () => {
    let finishDelete!: (value: unknown) => void;
    mocks.deleteSession.mockReturnValue(new Promise(resolve => { finishDelete = resolve; }));
    mocks.send.mockReturnValue(new AbortController());
    mocks.messages.mockResolvedValue({ code: 200, data: [
      { id: 1, role: 'user', content: 'question' },
      { id: 2, role: 'assistant', content: 'answer' },
    ] });
    const hook = renderHook(() => useAiChat());
    await act(async () => hook.result.current.handleSelectSession('a'));
    act(() => { void hook.result.current.handleDeleteSession('b'); hook.result.current.handleRetry(); });
    expect(mocks.send).not.toHaveBeenCalled();
    expect(hook.result.current.messages.map(m => m.content)).toEqual(['question', 'answer']);
    await act(async () => finishDelete({ code: 403 }));
    act(() => hook.result.current.handleRetry());
    expect(mocks.send).toHaveBeenCalledOnce();
    expect(mocks.send.mock.calls[0][0]).toMatchObject({ sessionId: 'a', message: 'question' });
    expect(hook.result.current.messages.map(m => m.content)).toEqual(['question']);
  });

  it.each(['before rerender', 'after rerender'] as const)('rejects retry from the snapshot before finalize %s', async timing => {
    let finishMessages!: (value: unknown) => void;
    mocks.messages.mockReturnValue(new Promise(resolve => { finishMessages = resolve; }));
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSuggestedPrompt('question'));
    const [, event, , complete] = mocks.send.mock.calls[0];
    await act(async () => { event({ type: 'SESSION', sessionId: 'a' }); event({ type: 'CONTENT', content: 'local answer' }); complete(); });
    const oldRetry = hook.result.current.handleRetry;
    act(() => hook.result.current.setInputValue('keep draft'));
    const serverMessages = [
      { id: 1, role: 'user', content: 'server question' },
      { id: 2, role: 'assistant', content: 'server answer' },
    ];
    await act(async () => {
      finishMessages({ code: 200, data: serverMessages });
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
      if (timing === 'before rerender') oldRetry();
    });
    if (timing === 'after rerender') act(() => oldRetry());
    expect(mocks.send).toHaveBeenCalledTimes(1);
    expect(hook.result.current.messages).toEqual(serverMessages);
    expect(hook.result.current.inputValue).toBe('keep draft');
    expect(hook.result.current.loading).toBe(false);
    expect(hook.result.current.isStreaming).toBe(false);
    act(() => hook.result.current.handleRetry());
    expect(mocks.send).toHaveBeenCalledTimes(2);
    expect(mocks.send.mock.calls[1][0]).toMatchObject({ sessionId: 'a', message: 'server question' });
    expect(hook.result.current.messages.map(m => m.content)).toEqual(['server question']);
  });

  it.each([
    ['send', 'before rerender'], ['send', 'after rerender'],
    ['suggested prompt', 'before rerender'], ['suggested prompt', 'after rerender'],
  ] as const)('rejects stale %s after finalize %s and permits the fresh callback', async (kind, timing) => {
    let finishMessages!: (value: unknown) => void;
    mocks.messages.mockReturnValue(new Promise(resolve => { finishMessages = resolve; }));
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSuggestedPrompt('question'));
    const [, event, , complete] = mocks.send.mock.calls[0];
    await act(async () => { event({ type: 'SESSION', sessionId: 'a' }); event({ type: 'CONTENT', content: 'local answer' }); complete(); });
    act(() => hook.result.current.setInputValue('keep draft'));
    const { handleSend, handleSuggestedPrompt } = hook.result.current;
    const staleSend = () => kind === 'send' ? handleSend() : handleSuggestedPrompt('suggestion');
    const serverMessages = [
      { id: 1, role: 'user', content: 'server question' },
      { id: 2, role: 'assistant', content: 'server answer' },
    ];
    await act(async () => {
      finishMessages({ code: 200, data: serverMessages });
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
      if (timing === 'before rerender') staleSend();
    });
    if (timing === 'after rerender') act(staleSend);
    expect(mocks.send).toHaveBeenCalledTimes(1);
    expect(hook.result.current.messages).toEqual(serverMessages);
    expect(hook.result.current.inputValue).toBe('keep draft');
    expect(hook.result.current.loading).toBe(false);
    expect(hook.result.current.isStreaming).toBe(false);
    act(() => kind === 'send' ? hook.result.current.handleSend() : hook.result.current.handleSuggestedPrompt('suggestion'));
    expect(mocks.send).toHaveBeenCalledTimes(2);
    expect(mocks.send.mock.calls[1][0]).toMatchObject({ sessionId: 'a', message: kind === 'send' ? 'keep draft' : 'suggestion' });
    expect(hook.result.current.messages.map(m => m.content)).toEqual(['server question', 'server answer', kind === 'send' ? 'keep draft' : 'suggestion']);
  });

  it('permits only one stream across views and hook instances, including synchronous sends', async () => {
    mocks.send.mockReturnValue(new AbortController());
    const first = renderHook(() => useAiChat());
    const second = renderHook(() => useAiChat());
    act(() => {
      first.result.current.handleSuggestedPrompt('one');
      first.result.current.handleSuggestedPrompt('two');
      second.result.current.handleSuggestedPrompt('three');
    });
    expect(mocks.send).toHaveBeenCalledTimes(1);
    await act(async () => {});
  });

  it('ignores out-of-order message responses after switching or starting a new chat', async () => {
    const pending: Array<(value: unknown) => void> = [];
    mocks.messages.mockImplementation(() => new Promise(resolve => pending.push(resolve)));
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSelectSession('a'));
    act(() => hook.result.current.handleSelectSession('b'));
    await act(async () => pending[1]({ code: 200, data: [{ id: 2, content: 'b' }] }));
    await act(async () => pending[0]({ code: 200, data: [{ id: 1, content: 'a' }] }));
    expect(hook.result.current.messages[0].content).toBe('b');
    act(() => hook.result.current.handleSelectSession('a'));
    act(() => hook.result.current.handleNewChat());
    await act(async () => pending[2]({ code: 200, data: [{ id: 1, content: 'a' }] }));
    expect(hook.result.current.messages).toEqual([]);
  });

  it('keeps background events out of the view and restores their snapshot on return', async () => {
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSuggestedPrompt('hello'));
    const [, event, , complete] = mocks.send.mock.calls[0];
    act(() => event({ type: 'SESSION', sessionId: 'a' }));
    act(() => hook.result.current.handleSelectSession('b'));
    await act(async () => {});
    act(() => {
      event({ type: 'CONTENT', content: 'background' });
      event({ type: 'THINKING', content: 'thought' });
      event({ type: 'STEP_CHECKPOINT', stepIndex: 3 });
      event({ type: 'SESSION', sessionId: 'a' });
    });
    expect(hook.result.current.currentSessionId).toBe('b');
    expect(hook.result.current.streamingContent).toBe('');
    expect(hook.result.current.checkpointSteps.size).toBe(0);
    act(() => hook.result.current.handleSelectSession('a'));
    expect(hook.result.current.streamingContent).toBe('background');
    expect(hook.result.current.streamingThinkingLog).toContain('thought');
    expect(hook.result.current.checkpointSteps.has(3)).toBe(true);
    act(() => hook.result.current.handleNewChat());
    act(() => complete());
    expect(hook.result.current.messages).toEqual([]);
    act(() => hook.result.current.handleSelectSession('a'));
    expect(hook.result.current.messages.map(m => m.content)).toContain('background');
    await act(async () => {});
  });

  it('delivers completion to a remounted subscriber', async () => {
    mocks.send.mockReturnValue(new AbortController());
    const first = renderHook(() => useAiChat());
    act(() => first.result.current.handleSuggestedPrompt('hello'));
    const [, event, , complete] = mocks.send.mock.calls[0];
    act(() => event({ type: 'SESSION', sessionId: 'a' }));
    first.unmount();
    const second = renderHook(() => useAiChat());
    act(() => { event({ type: 'CONTENT', content: 'answer' }); complete(); });
    expect(second.result.current.isStreaming).toBe(false);
    expect(second.result.current.messages.map(m => m.content)).toContain('answer');
    await act(async () => {});
  });

  it('stop aborts locally and ignores callbacks from the stopped stream', async () => {
    const controller = new AbortController();
    mocks.send.mockReturnValue(controller);
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSuggestedPrompt('hello'));
    const [, event, , complete] = mocks.send.mock.calls[0];
    act(() => { event({ type: 'SESSION', sessionId: 'a' }); event({ type: 'CONTENT', content: 'partial' }); });
    act(() => hook.result.current.handleStop());
    expect(controller.signal.aborted).toBe(true);
    act(() => { event({ type: 'CONTENT', content: 'late' }); complete(); });
    expect(hook.result.current.messages.at(-1)?.content).toContain('partial');
    expect(hook.result.current.messages.at(-1)?.content).not.toContain('late');
    await act(async () => {});
  });
});


describe('concurrent subscribers and request ordering', () => {
  it('blocks deletion across hooks while the stream ID is unknown or matches, and allows it after stop', async () => {
    mocks.send.mockReturnValue(new AbortController());
    const first = renderHook(() => useAiChat());
    const second = renderHook(() => useAiChat());
    act(() => first.result.current.handleSuggestedPrompt('hello'));
    const [, event, , complete] = mocks.send.mock.calls[0];
    await act(async () => second.result.current.handleDeleteSession('a'));
    expect(mocks.deleteSession).not.toHaveBeenCalled();
    act(() => event({ type: 'SESSION', sessionId: 'a' }));
    await act(async () => second.result.current.handleDeleteSession('a'));
    expect(mocks.deleteSession).not.toHaveBeenCalled();
    expect(mocks.warning).toHaveBeenCalledTimes(2);
    await act(async () => second.result.current.handleDeleteSession('b'));
    expect(mocks.deleteSession).toHaveBeenCalledWith('b');
    act(() => first.result.current.handleStop());
    await act(async () => first.result.current.handleDeleteSession('a'));
    act(() => complete());
    expect(mocks.deleteSession).toHaveBeenCalledWith('a');
    first.unmount();
    second.unmount();
    const next = renderHook(() => useAiChat());
    expect(next.result.current.currentSessionId).toBeNull();
    expect(next.result.current.messages).toEqual([]);
  });

  it.each(['resolve', 'reject'] as const)('blocks cross-hook sends during a pending delete and releases on %s', async outcome => {
    let resolve!: (value: unknown) => void;
    let reject!: (error: Error) => void;
    mocks.deleteSession.mockReturnValue(new Promise((res, rej) => { resolve = res; reject = rej; }));
    mocks.send.mockReturnValue(new AbortController());
    const first = renderHook(() => useAiChat());
    const second = renderHook(() => useAiChat());
    act(() => { void first.result.current.handleDeleteSession('a'); second.result.current.handleSuggestedPrompt('hello'); });
    expect(mocks.send).not.toHaveBeenCalled();
    expect(mocks.warning).toHaveBeenCalledOnce();
    await act(async () => { if (outcome === 'resolve') resolve({ code: 200 }); else reject(new Error('failed')); });
    act(() => second.result.current.handleSuggestedPrompt('hello'));
    expect(mocks.send).toHaveBeenCalledOnce();
  });

  it('keeps other subscribers attached when one switches away or unmounts, and broadcasts stop', async () => {
    mocks.send.mockReturnValue(new AbortController());
    const first = renderHook(() => useAiChat());
    act(() => first.result.current.handleSuggestedPrompt('hello'));
    const [, event] = mocks.send.mock.calls[0];
    act(() => event({ type: 'SESSION', sessionId: 'a' }));
    const second = renderHook(() => useAiChat());
    const third = renderHook(() => useAiChat());
    act(() => second.result.current.handleNewChat());
    third.unmount();
    act(() => event({ type: 'CONTENT', content: 'partial' }));
    expect(first.result.current.streamingContent).toBe('partial');
    act(() => second.result.current.handleSelectSession('a'));
    act(() => first.result.current.handleStop());
    for (const hook of [first, second]) {
      expect(hook.result.current.isStreaming).toBe(false);
      expect(hook.result.current.messages.at(-1)?.content).toContain('partial');
      expect(hook.result.current.messages.at(-1)?.content).toContain('chat.stopped');
    }
    await act(async () => {});
  });

  it('does not let a prior run overwrite the cached result of a newer run in the same session', async () => {
    const pending: Array<(value: unknown) => void> = [];
    mocks.messages.mockImplementation(() => new Promise(resolve => pending.push(resolve)));
    mocks.send.mockReturnValue(new AbortController());
    const first = renderHook(() => useAiChat());
    act(() => first.result.current.handleSuggestedPrompt('first'));
    const [, event1, , complete1] = mocks.send.mock.calls[0];
    await act(async () => { event1({ type: 'SESSION', sessionId: 'a' }); event1({ type: 'CONTENT', content: 'old' }); complete1(); });
    act(() => first.result.current.handleSuggestedPrompt('second'));
    const [, event2, , complete2] = mocks.send.mock.calls[1];
    act(() => event2({ type: 'CONTENT', content: 'new' }));
    first.unmount();
    act(() => complete2());
    await act(async () => pending[0]({ code: 200, data: [{ id: 9, content: 'stale' }] }));
    const next = renderHook(() => useAiChat());
    expect(next.result.current.messages.map(m => m.content)).toContain('new');
    expect(next.result.current.messages.map(m => m.content)).not.toContain('stale');
    await act(async () => {});
  });

  it.each(['resolve', 'reject'] as const)('ignores a delete %s after epoch change', async outcome => {
    let resolve!: (value: unknown) => void;
    let reject!: (error: Error) => void;
    mocks.deleteSession.mockReturnValue(new Promise((res, rej) => { resolve = res; reject = rej; }));
    const hook = renderHook(() => useAiChat());
    act(() => { void hook.result.current.handleDeleteSession('a'); });
    act(changeEpoch);
    act(() => hook.result.current.handleSelectSession('a'));
    await act(async () => { if (outcome === 'resolve') resolve({ code: 200 }); else reject(new Error('late')); });
    expect(hook.result.current.currentSessionId).toBe('a');
    expect(mocks.sessions).toHaveBeenCalledTimes(2);
  });

  it('does not allow an older session list to replace the post-delete list', async () => {
    const pending: Array<(value: unknown) => void> = [];
    mocks.sessions.mockImplementation(() => new Promise(resolve => pending.push(resolve)));
    const hook = renderHook(() => useAiChat());
    await act(async () => { await hook.result.current.handleDeleteSession('a'); });
    expect(pending).toHaveLength(2);
    await act(async () => pending[1]({ code: 200, data: [{ id: 'new' }] }));
    await act(async () => pending[0]({ code: 200, data: [{ id: 'old' }] }));
    expect(hook.result.current.sessions.map(s => s.id)).toEqual(['new']);
    expect(hook.result.current.loadingSessions).toBe(false);
  });
});

describe('deletion and completion races', () => {
  it('refreshes every subscriber and rejects all pre-delete list responses', async () => {
    const pending: Array<(value: unknown) => void> = [];
    mocks.sessions.mockImplementation(() => new Promise(resolve => pending.push(resolve)));
    const first = renderHook(() => useAiChat());
    const second = renderHook(() => useAiChat());
    await act(async () => first.result.current.handleDeleteSession('a'));
    expect(pending).toHaveLength(4);
    await act(async () => {
      pending[2]({ code: 200, data: [{ id: 'fresh-1' }] });
      pending[3]({ code: 200, data: [{ id: 'fresh-2' }] });
      pending[0]({ code: 200, data: [{ id: 'stale-1' }] });
      pending[1]({ code: 200, data: [{ id: 'stale-2' }] });
    });
    expect(first.result.current.sessions.map(s => s.id)).toEqual(['fresh-1']);
    expect(second.result.current.sessions.map(s => s.id)).toEqual(['fresh-2']);
  });

  it.each(['success', 'failure'] as const)('waits for a matching deletion before applying a finalize response (%s)', async outcome => {
    let finishDelete!: (value: unknown) => void;
    let finishMessages!: (value: unknown) => void;
    mocks.deleteSession.mockReturnValue(new Promise(resolve => { finishDelete = resolve; }));
    mocks.messages.mockReturnValue(new Promise(resolve => { finishMessages = resolve; }));
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSuggestedPrompt('hello'));
    const [, event, , complete] = mocks.send.mock.calls[0];
    await act(async () => { event({ type: 'SESSION', sessionId: 'a' }); complete(); });
    act(() => { void hook.result.current.handleDeleteSession('a'); });
    await act(async () => finishMessages({ code: 200, data: [{ id: 1, content: 'server' }] }));
    expect(hook.result.current.messages.map(m => m.content)).not.toContain('server');
    await act(async () => finishDelete({ code: outcome === 'success' ? 200 : 403 }));
    expect(hook.result.current.messages.map(m => m.content).includes('server')).toBe(outcome === 'failure');
  });

  it('finalizes while an unrelated deletion remains pending', async () => {
    let finishDelete!: (value: unknown) => void;
    mocks.deleteSession.mockReturnValue(new Promise(resolve => { finishDelete = resolve; }));
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSuggestedPrompt('hello'));
    const [, event, , complete] = mocks.send.mock.calls[0];
    act(() => { event({ type: 'SESSION', sessionId: 'a' }); void hook.result.current.handleDeleteSession('b'); });
    await act(async () => complete());
    expect(mocks.messages).toHaveBeenCalledWith('a');
    await act(async () => finishDelete({ code: 200 }));
  });

  it.each(['failure', 'success', 'epoch'] as const)('rechecks a matching deletion before starting finalize (%s)', async outcome => {
    let finishSave!: (value: unknown) => void;
    let finishDelete!: (value: unknown) => void;
    mocks.save.mockReturnValue(new Promise(resolve => { finishSave = resolve; }));
    mocks.deleteSession.mockReturnValue(new Promise(resolve => { finishDelete = resolve; }));
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSuggestedPrompt('hello'));
    const [, event, , complete] = mocks.send.mock.calls[0];
    act(() => { event({ type: 'SESSION', sessionId: 'a' }); event({ type: 'THINKING', content: 'thought' }); complete(); });
    act(() => { void hook.result.current.handleDeleteSession('a'); });
    await act(async () => finishSave({ code: 200 }));
    expect(mocks.messages).not.toHaveBeenCalled();
    if (outcome === 'epoch') act(changeEpoch);
    await act(async () => finishDelete({ code: outcome === 'success' ? 200 : 403 }));
    expect(mocks.messages).toHaveBeenCalledTimes(outcome === 'failure' ? 1 : 0);
  });
  it('reloads sessions after an epoch change without letting the old request win', async () => {
    const pending: Array<(value: unknown) => void> = [];
    mocks.sessions.mockImplementation(() => new Promise(resolve => pending.push(resolve)));
    const hook = renderHook(() => useAiChat());
    expect(hook.result.current.loadingSessions).toBe(true);
    act(changeEpoch);
    expect(pending).toHaveLength(2);
    await act(async () => pending[1]({ code: 200, data: [{ id: 'new' }] }));
    await act(async () => pending[0]({ code: 200, data: [{ id: 'old' }] }));
    expect(hook.result.current.sessions.map(s => s.id)).toEqual(['new']);
    expect(hook.result.current.loadingSessions).toBe(false);
  });

  it('broadcasts successful deletion and invalidates another hook’s pending message load and stale sender', async () => {
    let resolveMessages!: (value: unknown) => void;
    mocks.messages.mockReturnValue(new Promise(resolve => { resolveMessages = resolve; }));
    mocks.send.mockReturnValue(new AbortController());
    const first = renderHook(() => useAiChat());
    const second = renderHook(() => useAiChat());
    act(() => second.result.current.handleSelectSession('a'));
    const staleSend = second.result.current.handleSuggestedPrompt;
    await act(async () => first.result.current.handleDeleteSession('a'));
    await act(async () => resolveMessages({ code: 200, data: [{ id: 1, content: 'deleted' }] }));
    act(() => staleSend('should not send'));
    expect(second.result.current.currentSessionId).toBeNull();
    expect(second.result.current.messages).toEqual([]);
    expect(mocks.send).not.toHaveBeenCalled();
  });

  it('does not broadcast a business-level delete failure', async () => {
    mocks.deleteSession.mockResolvedValue({ code: 403 });
    const first = renderHook(() => useAiChat());
    const second = renderHook(() => useAiChat());
    act(() => second.result.current.handleSelectSession('a'));
    await act(async () => first.result.current.handleDeleteSession('a'));
    expect(second.result.current.currentSessionId).toBe('a');
  });

  it.each(['delete', 'new run'] as const)('does not issue old finalize after %s while save is pending', async scenario => {
    let resolveSave!: (value: unknown) => void;
    mocks.save.mockReturnValue(new Promise(resolve => { resolveSave = resolve; }));
    mocks.send.mockReturnValue(new AbortController());
    const hook = renderHook(() => useAiChat());
    act(() => hook.result.current.handleSuggestedPrompt('first'));
    const [, event, , complete] = mocks.send.mock.calls[0];
    act(() => { event({ type: 'SESSION', sessionId: 'a' }); event({ type: 'THINKING', content: 'thinking' }); complete(); });
    if (scenario === 'delete') await act(async () => hook.result.current.handleDeleteSession('a'));
    else act(() => hook.result.current.handleSuggestedPrompt('second'));
    await act(async () => resolveSave({ code: 200 }));
    expect(mocks.messages).not.toHaveBeenCalled();
  });

  it('retains a stopped snapshot for multiple later consumers', async () => {
    mocks.send.mockReturnValue(new AbortController());
    const first = renderHook(() => useAiChat());
    act(() => first.result.current.handleSuggestedPrompt('hello'));
    const [, event] = mocks.send.mock.calls[0];
    act(() => { event({ type: 'SESSION', sessionId: 'a' }); event({ type: 'CONTENT', content: 'partial' }); });
    act(() => first.result.current.handleStop());
    first.unmount();
    const second = renderHook(() => useAiChat());
    const third = renderHook(() => useAiChat());
    expect(second.result.current.messages.at(-1)?.content).toContain('partial');
    expect(third.result.current.messages.at(-1)?.content).toContain('partial');
    await act(async () => {});
  });
});
