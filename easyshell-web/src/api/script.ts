import request from './request';
import i18n from '../i18n';
import type { ApiResponse, Script, ScriptRequest, AgentEvent } from '../types';

export function getScriptList(): Promise<ApiResponse<Script[]>> {
  return request.get('/v1/script/list');
}

export function getScriptTemplates(): Promise<ApiResponse<Script[]>> {
  return request.get('/v1/script/templates');
}

export function getUserScripts(): Promise<ApiResponse<Script[]>> {
  return request.get('/v1/script/user-scripts');
}

export function getScript(id: number): Promise<ApiResponse<Script>> {
  return request.get(`/v1/script/${id}`);
}

export function createScript(data: ScriptRequest): Promise<ApiResponse<Script>> {
  return request.post('/v1/script', data);
}

export function updateScript(id: number, data: ScriptRequest): Promise<ApiResponse<Script>> {
  return request.put(`/v1/script/${id}`, data);
}

export function deleteScript(id: number): Promise<ApiResponse<void>> {
  return request.delete(`/v1/script/${id}`);
}

export interface ScriptGenerateRequest {
  prompt: string;
  os: string;
  scriptType: string;
  language: string;
  existingScript?: string;
}

export function generateScript(
  data: ScriptGenerateRequest,
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

  fetch('/api/v1/ai/script/generate', {
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
        if (response.status === 401 || response.status === 403) {
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
