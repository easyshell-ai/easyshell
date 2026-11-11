import request from './request';
import { getToken, clearSession, getSessionEpoch, subscribeSessionEpoch } from './request';
import i18n from '../i18n';
import type {
  ApiResponse,
  PageResponse,
  Task,
  AiConfigVO,
  AiConfigSaveRequest,
  AiTestRequest,
  AiTestResult,
  AiRiskRulesVO,
  AiRiskRulesSaveRequest,
  AiRiskAssessRequest,
  RiskAssessment,
  AiChatSession,
  AiChatMessage,
  AiChatRequest,
  AiChatResponse,
  AiScheduledTask,
  AiScheduledTaskRequest,
  AiInspectReport,
  AiAlertRequest,
  AiAlertAnalysis,
  BuiltInTemplate,
  AgentEvent,
} from '../types';

export function getAiConfig(): Promise<ApiResponse<AiConfigVO>> {
  return request.get('/v1/ai/config');
}

export function saveAiConfig(data: AiConfigSaveRequest): Promise<ApiResponse<null>> {
  return request.put('/v1/ai/config', data);
}

export function testAiConnection(data: AiTestRequest): Promise<ApiResponse<AiTestResult>> {
  return request.post('/v1/ai/config/test', data);
}

export function getRiskRules(): Promise<ApiResponse<AiRiskRulesVO>> {
  return request.get('/v1/ai/risk/rules');
}

export function saveRiskRules(data: AiRiskRulesSaveRequest): Promise<ApiResponse<null>> {
  return request.put('/v1/ai/risk/rules', data);
}

export function assessRisk(data: AiRiskAssessRequest): Promise<ApiResponse<RiskAssessment>> {
  return request.post('/v1/ai/risk/assess', data);
}

export function getChatSessions(): Promise<ApiResponse<AiChatSession[]>> {
  return request.get('/v1/ai/chat/sessions');
}

export function sendChat(data: AiChatRequest, timeout?: number): Promise<ApiResponse<AiChatResponse>> {
  return request.post('/v1/ai/chat', data, { timeout: timeout || 120000 });
}

export function getChatMessages(sessionId: string): Promise<ApiResponse<AiChatMessage[]>> {
  return request.get(`/v1/ai/chat/sessions/${sessionId}/messages`);
}

export function deleteChatSession(sessionId: string): Promise<ApiResponse<null>> {
  return request.delete(`/v1/ai/chat/sessions/${sessionId}`);
}

export function approveExecution(taskId: string): Promise<ApiResponse<null>> {
  return request.post(`/v1/ai/chat/approve/${taskId}`);
}

export function rejectExecution(taskId: string): Promise<ApiResponse<null>> {
  return request.post(`/v1/ai/chat/reject/${taskId}`);
}

export function confirmPlan(sessionId: string, planId: string): Promise<ApiResponse<null>> {
  return request.post(`/v1/ai/chat/sessions/${sessionId}/plan/confirm`, { planId });
}

export function rejectPlan(sessionId: string, planId: string): Promise<ApiResponse<null>> {
  return request.post(`/v1/ai/chat/sessions/${sessionId}/plan/reject`, { planId });
}

export function approveCheckpoint(sessionId: string, stepIndex: number): Promise<ApiResponse<null>> {
  return request.post(`/v1/ai/chat/sessions/${sessionId}/checkpoint/${stepIndex}/approve`);
}

export function rejectCheckpoint(sessionId: string, stepIndex: number): Promise<ApiResponse<null>> {
  return request.post(`/v1/ai/chat/sessions/${sessionId}/checkpoint/${stepIndex}/reject`);
}

export function saveProcessData(sessionId: string, processData: string): Promise<ApiResponse<null>> {
  return request.post(`/v1/ai/chat/sessions/${sessionId}/process-data`, { processData });
}

export function getPendingApprovals(): Promise<ApiResponse<Task[]>> {
  return request.get('/v1/ai/chat/pending-approvals');
}

const STREAM_TIMEOUT_MS = 180_000;

export function sendChatStream(
  data: AiChatRequest,
  onEvent: (event: AgentEvent) => void,
  onError: (error: Error) => void,
  onComplete: () => void,
): AbortController {
  const controller = new AbortController();
  const epoch = getSessionEpoch();
  const isCurrentSession = () => epoch === getSessionEpoch();
  let fetchAbort: AbortController | undefined;
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
  let timeoutId: ReturnType<typeof setTimeout> | undefined;
  const cleanup = () => {
    clearTimeout(timeoutId);
    unsubscribe();
    controller.signal.removeEventListener('abort', cancel);
  };
  const cancel = () => {
    fetchAbort?.abort();
    if (reader) void reader.cancel().catch(() => {});
    cleanup();
  };
  const unsubscribe = subscribeSessionEpoch(() => {
    if (!isCurrentSession()) cancel();
  });
  controller.signal.addEventListener('abort', cancel, { once: true });

  const doFetch = () => {
    if (controller.signal.aborted || !isCurrentSession()) { cleanup(); return; }
    const token = getToken();
    let receivedTerminalEvent = false;
    let timedOut = false;

    const wrappedOnEvent = (event: AgentEvent) => {
      if (!isCurrentSession()) return;
      if (event.type === 'DONE' || event.type === 'ERROR') {
        receivedTerminalEvent = true;
      }
      onEvent(event);
    };

    // Keep timeout distinct from user cancellation, which is silent.
    fetchAbort = new AbortController();
    const activeFetch = fetchAbort;
    timeoutId = setTimeout(() => {
      if (!receivedTerminalEvent && !controller.signal.aborted) {
        timedOut = true;
        activeFetch.abort(new DOMException('Stream timeout', 'TimeoutError'));
      }
    }, STREAM_TIMEOUT_MS);

    fetch('/api/v1/ai/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': token ? `Bearer ${token}` : '',
        'Accept': 'text/event-stream',
        'Accept-Language': i18n.language || 'zh-CN',
      },
      body: JSON.stringify(data),
      signal: activeFetch.signal,
    })
      .then(async (response) => {
        clearTimeout(timeoutId);
        if (!isCurrentSession() || controller.signal.aborted) { cleanup(); return; }
        if (!response.ok) {
          if (response.status === 401) {
            cleanup();
            clearSession();
            window.location.href = '/login';
            return;
          }
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }
        reader = response.body?.getReader();
        if (!reader) throw new Error('No response body');
        const activeReader = reader;

        const decoder = new TextDecoder();
        let buffer = '';

        try {
          while (true) {
            const { done, value } = await activeReader.read();
            if (!isCurrentSession() || controller.signal.aborted) { cleanup(); return; }
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
              if (line.startsWith('data:')) {
                const jsonStr = line.slice(5).trim();
                if (jsonStr) {
                  try {
                    const event: AgentEvent = JSON.parse(jsonStr);
                    wrappedOnEvent(event);
                  } catch { }
                }
              }
            }

            if (receivedTerminalEvent) {
              // After receiving DONE/ERROR, drain remaining buffer then let stream close naturally
              // DO NOT call reader.cancel() - it triggers server-side cancellation before doFinally runs
              if (buffer.startsWith('data:')) {
                const jsonStr = buffer.slice(5).trim();
                if (jsonStr) {
                  try {
                    const event: AgentEvent = JSON.parse(jsonStr);
                    wrappedOnEvent(event);
                  } catch { }
                }
                buffer = '';
              }
              // Simply break the loop - the server will close the stream naturally
              break;
            }
          }
        } catch (readErr) {
          if (!receivedTerminalEvent) throw readErr;
        }

        if (buffer.startsWith('data:')) {
          const jsonStr = buffer.slice(5).trim();
          if (jsonStr) {
            try {
              const event: AgentEvent = JSON.parse(jsonStr);
              wrappedOnEvent(event);
            } catch { }
          }
        }

        if (isCurrentSession() && !controller.signal.aborted) onComplete();
        cleanup();
      })
      .catch((err) => {
        clearTimeout(timeoutId);

        if (controller.signal.aborted || !isCurrentSession()) { cleanup(); return; }

        if (receivedTerminalEvent) {
          cleanup();
          onComplete();
          return;
        }

        // A failed POST may already have side effects; without server-side
        // idempotency, even a network error must not trigger another request.
        cleanup();
        onError(timedOut ? new DOMException('Stream timeout', 'TimeoutError') :
          err instanceof Error ? err : new Error(String(err)));
      });
  };

  doFetch();
  return controller;
}

export function getScheduledTasks(): Promise<ApiResponse<AiScheduledTask[]>> {
  return request.get('/v1/ai/scheduled-tasks');
}

export function getScheduledTask(id: number): Promise<ApiResponse<AiScheduledTask>> {
  return request.get(`/v1/ai/scheduled-tasks/${id}`);
}

export function createScheduledTask(data: AiScheduledTaskRequest): Promise<ApiResponse<AiScheduledTask>> {
  return request.post('/v1/ai/scheduled-tasks', data);
}

export function updateScheduledTask(id: number, data: AiScheduledTaskRequest): Promise<ApiResponse<AiScheduledTask>> {
  return request.put(`/v1/ai/scheduled-tasks/${id}`, data);
}

export function deleteScheduledTask(id: number): Promise<ApiResponse<null>> {
  return request.delete(`/v1/ai/scheduled-tasks/${id}`);
}

export function enableScheduledTask(id: number): Promise<ApiResponse<null>> {
  return request.post(`/v1/ai/scheduled-tasks/${id}/enable`);
}

export function disableScheduledTask(id: number): Promise<ApiResponse<null>> {
  return request.post(`/v1/ai/scheduled-tasks/${id}/disable`);
}

export function runScheduledTask(id: number): Promise<ApiResponse<null>> {
  return request.post(`/v1/ai/scheduled-tasks/${id}/run`);
}

export function getTemplates(): Promise<ApiResponse<BuiltInTemplate[]>> {
  return request.get('/v1/ai/scheduled-tasks/templates');
}

export function analyzeAlert(data: AiAlertRequest): Promise<ApiResponse<AiAlertAnalysis>> {
  return request.post('/v1/ai/scheduled-tasks/alert/analyze', data);
}

export function getInspectReports(page: number = 0, size: number = 10): Promise<ApiResponse<PageResponse<AiInspectReport>>> {
  return request.get(`/v1/ai/inspect/reports?page=${page}&size=${size}`);
}

export function getInspectReport(id: number): Promise<ApiResponse<AiInspectReport>> {
  return request.get(`/v1/ai/inspect/reports/${id}`);
}

// GitHub Copilot OAuth Device Flow
export function copilotRequestDeviceCode(): Promise<ApiResponse<{ deviceCode: string; userCode: string; verificationUri: string; expiresIn: number; interval: number }>> {
  return request.post('/v1/ai/copilot/device-code');
}

export function copilotPollToken(deviceCode: string): Promise<ApiResponse<{ status: string; message: string }>> {
  return request.post('/v1/ai/copilot/poll-token', { deviceCode });
}

export function copilotGetStatus(): Promise<ApiResponse<{ authenticated: boolean }>> {
  return request.get('/v1/ai/copilot/status');
}

export function copilotLogout(): Promise<ApiResponse<null>> {
  return request.delete('/v1/ai/copilot/logout');
}

export function getCopilotModels(): Promise<ApiResponse<{ id: string; name: string; version: string }[]>> {
  return request.get('/v1/ai/copilot/models');
}

export function getProviderModels(provider: string): Promise<ApiResponse<{ id: string; name: string }[]>> {
  return request.get('/v1/ai/config/models', { params: { provider } });
}
