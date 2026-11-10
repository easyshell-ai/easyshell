import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Empty, Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { getToken } from '../../../api/request';
import { buildWorkflowRunWsUrl } from '../../../utils/ws';
import { parseAnsi } from './ansi';
import '../workflow.css';

/**
 * 运行实时日志（§6.4：/ws/workflow/run/{runId}，token 走 query——浏览器没法给 WS 设 header，
 * 与 pages/task/index.tsx 修好后的范式一致；script-exec 的节点日志另走 /ws/task/log/{taskId}）。
 *
 * S6：ANSI 解析产 React 元素（parseAnsi → <span style>），全仓 0 处 dangerouslySetInnerHTML。
 */

const MAX_LINES = 5000;

export interface RunLogPanelProps {
  runId: string;
  /** 运行结束后停止接收（终态由父组件判定） */
  active: boolean;
}

interface LogEntry {
  id: number;
  text: string;
}

const RunLogPanel: React.FC<RunLogPanelProps> = ({ runId, active }) => {
  const { t } = useTranslation();
  const [lines, setLines] = useState<LogEntry[]>([]);
  const [status, setStatus] = useState<'idle' | 'connected' | 'disconnected'>('idle');
  const wsRef = useRef<WebSocket | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const seqRef = useRef(0);

  const connect = useCallback(() => {
    if (wsRef.current) wsRef.current.close();
    setLines([]);
    setStatus('idle');
    const ws = new WebSocket(buildWorkflowRunWsUrl(runId, getToken(), window.location));
    ws.onopen = () => setStatus('connected');
    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data) as { type?: string; nodeKey?: string; message?: string };
        const prefix = data.nodeKey ? `[${data.nodeKey}] ` : '';
        const text = `${prefix}${data.type || 'EVENT'}${data.message ? `: ${data.message}` : ''}`;
        seqRef.current += 1;
        setLines((prev) => [...prev, { id: seqRef.current, text }].slice(-MAX_LINES));
      } catch {
        seqRef.current += 1;
        setLines((prev) => [...prev, { id: seqRef.current, text: String(event.data) }].slice(-MAX_LINES));
      }
    };
    ws.onclose = () => setStatus('disconnected');
    ws.onerror = () => setStatus('disconnected');
    wsRef.current = ws;
  }, [runId]);

  useEffect(() => {
    if (!active) {
      // 终态运行不再建连：服务端会立即关闭，浏览器控制台留下无意义的 WS 错误。
      wsRef.current?.close();
      wsRef.current = null;
      setStatus('idle');
      return;
    }
    connect();
    return () => {
      wsRef.current?.close();
      wsRef.current = null;
    };
  }, [connect, active]);

  useEffect(() => {
    if (containerRef.current) containerRef.current.scrollTop = containerRef.current.scrollHeight;
  }, [lines]);

  const rendered = useMemo(
    () =>
      lines.map((line) => (
        <div key={line.id} className="wf-log-line">
          {parseAnsi(line.text).map((segment, index) => (
            <span
              key={index}
              style={{
                color: segment.style.color,
                background: segment.style.background,
                fontWeight: segment.style.bold ? 600 : undefined,
                opacity: segment.style.dim ? 0.6 : undefined,
                fontStyle: segment.style.italic ? 'italic' : undefined,
                textDecoration: segment.style.underline ? 'underline' : undefined,
              }}
            >
              {segment.text}
            </span>
          ))}
        </div>
      )),
    [lines]
  );

  return (
    <div>
      <Space size={8} style={{ marginBottom: 8 }}>
        <Tag color={status === 'connected' ? 'green' : status === 'disconnected' ? 'red' : 'default'}>
          {t(`workflow.run.ws.${status}`)}
        </Tag>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          /ws/workflow/run/{runId}
        </Typography.Text>
      </Space>
      <div
        ref={containerRef}
        className="wf-log-pane"
        role="log"
        aria-live="polite"
        aria-label={t('workflow.run.logLabel')}
        data-testid="run-log"
      >
        {lines.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('workflow.run.noLogs')} /> : rendered}
      </div>
    </div>
  );
};

export default RunLogPanel;
