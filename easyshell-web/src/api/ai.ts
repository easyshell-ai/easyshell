import request from './request';
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

export function confirmPlan(sessionId: string): Promise<ApiResponse<null>> {
  return request.post(`/v1/ai/chat/sessions/${sessionId}/plan/confirm`);
}

export function rejectPlan(sessionId: string): Promise<ApiResponse<null>> {
  return request.post(`/v1/ai/chat/sessions/${sessionId}/plan/reject`);
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

export function sendChatStream(
  data: AiChatRequest,
  onEvent: (event: AgentEvent) => void,
  onError: (error: Error) => void,
  onComplete: () => void,
): AbortController {
  const controller = new AbortController();
  const token = localStorage.getItem('token');
  let receivedTerminalEvent = false;

  const wrappedOnEvent = (event: AgentEvent) => {
    if (event.type === 'DONE' || event.type === 'ERROR') {
      receivedTerminalEvent = true;
    }
    onEvent(event);
  };

  fetch('/api/v1/ai/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': token ? `Bearer ${token}` : '',
      'Accept': 'text/event-stream',
      'Accept-Language': i18n.language || 'zh-CN',
    },
    body: JSON.stringify(data),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        if (response.status === 401) {
          localStorage.removeItem('token');
          window.location.href = '/login';
          return;
        }
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }
      const reader = response.body?.getReader();
      if (!reader) throw new Error('No response body');

      const decoder = new TextDecoder();
      let buffer = '';

      try {
        while (true) {
          const { done, value } = await reader.read();
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
            // Drain any remaining data in the buffer before closing
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
            reader.cancel().catch(() => {});
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

      onComplete();
    })
    .catch((err) => {
      if (err.name === 'AbortError') return;
      if (receivedTerminalEvent) {
        onComplete();
        return;
      }
      onError(err);
    });

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
