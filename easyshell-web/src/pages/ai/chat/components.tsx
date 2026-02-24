import { useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Collapse, Tag, Tooltip } from 'antd';
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  SyncOutlined,
  ThunderboltOutlined,
  StopOutlined,
  ExclamationCircleOutlined,
  RobotOutlined,
  MinusCircleOutlined,
  DesktopOutlined,
  AuditOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import type { ApprovalRequest } from './types';
import type { ExecutionPlan } from '../../../types';

export const StepStatusIcon: React.FC<{ status: string }> = ({ status }) => {
  switch (status) {
    case 'completed': return <CheckCircleOutlined style={{ color: '#52c41a' }} />;
    case 'running': return <SyncOutlined spin style={{ color: '#1677ff' }} />;
    case 'failed': return <StopOutlined style={{ color: '#ff4d4f' }} />;
    case 'skipped': return <MinusCircleOutlined style={{ color: '#999' }} />;
    default: return <ClockCircleOutlined style={{ color: '#d9d9d9' }} />;
  }
};

export const ExecutionPlanView: React.FC<{ plan: ExecutionPlan; currentStepIndex: number }> = ({ plan, currentStepIndex }) => (
  <div style={{ marginBottom: 12 }}>
    <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 8 }}>{plan.summary}</div>
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      {plan.steps.map((step) => {
        const status = step.status || (step.index < currentStepIndex ? 'completed' : step.index === currentStepIndex ? 'running' : 'pending');
        const stepContent = (
          <div
            key={step.index}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              padding: '4px 8px',
              borderLeft: status === 'running' ? '3px solid #1677ff' : '3px solid transparent',
              borderRadius: 2,
              background: status === 'running' ? 'rgba(22,119,255,0.04)' : 'transparent',
              fontSize: 13,
              flexWrap: 'wrap',
            }}
          >
            <StepStatusIcon status={status} />
            <span style={{ color: '#999', fontSize: 12, minWidth: 16 }}>#{step.index + 1}</span>
            <span style={{ flex: 1, minWidth: 0 }}>{step.description}</span>
            {step.parallelGroup != null && (
              <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px', margin: 0 }}>
                <ThunderboltOutlined /> P{step.parallelGroup}
              </Tag>
            )}
            {step.agent && (
              <span style={{
                fontSize: 11,
                padding: '1px 6px',
                borderRadius: 4,
                background: 'rgba(22,119,255,0.08)',
                color: '#1677ff',
              }}>
                {step.agent}
              </span>
            )}
            {step.hosts && step.hosts.length > 0 && step.hosts.map(host => (
              <Tag key={host} style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px', margin: 0 }} icon={<DesktopOutlined />}>
                {host}
              </Tag>
            ))}
            {step.error && (
              <span style={{ fontSize: 11, color: '#ff4d4f', maxWidth: 150, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {step.error}
              </span>
            )}
            {step.result && !step.error && (
              <span style={{ fontSize: 11, color: '#999', maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {step.result}
              </span>
            )}
          </div>
        );

        if (step.rollbackHint) {
          return (
            <Tooltip key={step.index} title={step.rollbackHint} placement="right">
              {stepContent}
            </Tooltip>
          );
        }
        return <div key={step.index}>{stepContent}</div>;
      })}
    </div>
  </div>
);

export const ToolCallView: React.FC<{ toolName: string; toolArgs: string; toolResult?: string }> = ({ toolName, toolArgs, toolResult }) => {
  const { t } = useTranslation();
  const isComplete = toolResult !== undefined;
  return (
    <Collapse
      size="small"
      style={{ marginBottom: 6, border: isComplete ? '1px solid #d9f7be' : '1px solid #ffe58f', overflow: 'hidden', width: '100%' }}
      items={[{
        key: '1',
        label: (
          <span style={{ fontSize: 12, display: 'flex', alignItems: 'center', gap: 6 }}>
            <ThunderboltOutlined style={{ color: isComplete ? '#52c41a' : '#faad14' }} />
            <span style={{ fontFamily: "'SFMono-Regular', Consolas, monospace", fontWeight: 500 }}>{toolName}</span>
            {isComplete ? (
              <CheckCircleOutlined style={{ color: '#52c41a', marginLeft: 'auto' }} />
            ) : (
              <SyncOutlined spin style={{ color: '#1677ff', marginLeft: 'auto' }} />
            )}
          </span>
        ),
        children: (
          <div style={{ fontSize: 12 }}>
            {toolArgs && (
              <div style={{ marginBottom: toolResult ? 8 : 0 }}>
                <div style={{ color: '#999', marginBottom: 2 }}>{t('components.toolCall.params')}</div>
                <pre style={{
                  background: '#f5f5f5',
                  padding: 8,
                  borderRadius: 4,
                  fontSize: 11,
                  fontFamily: 'monospace',
                  margin: 0,
                  overflowX: 'auto',
                  maxHeight: 120,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                }}>{toolArgs}</pre>
              </div>
            )}
            {toolResult !== undefined && (
              <div>
                <div style={{ color: '#999', marginBottom: 2 }}>{t('components.toolCall.result')}</div>
                <pre style={{
                  background: '#f5f5f5',
                  padding: 8,
                  borderRadius: 4,
                  fontSize: 11,
                  fontFamily: 'monospace',
                  margin: 0,
                  overflowX: 'auto',
                  maxHeight: 200,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                }}>{toolResult}</pre>
              </div>
            )}
          </div>
        ),
      }]}
    />
  );
};

export const ThinkingLog: React.FC<{ messages: string[] }> = ({ messages }) => {
  const containerRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [messages]);

  if (messages.length === 0) return null;

  // Helper: check if msg starts with any of the given bracketed keywords
  const bracketMatch = (msg: string, ...keywords: string[]): boolean =>
    keywords.some(k => msg.startsWith(`[${k}]`) || msg.startsWith(`[${k}:`));

  const getEntryStyle = (msg: string, isLatest: boolean): {
    icon: React.ReactNode;
    color: string;
    fontWeight?: number;
    borderBottom?: string;
  } => {
    // --- Iteration divider ---
    if (msg.startsWith('---') && msg.endsWith('---')) {
      return {
        icon: null,
        color: '#1677ff',
        fontWeight: 600,
        borderBottom: '1px solid rgba(22,119,255,0.2)',
      };
    }
    // [反思] / [Reflection]
    if (bracketMatch(msg, '反思', 'Reflection')) {
      return {
        icon: <ExclamationCircleOutlined style={{ color: '#faad14', fontSize: 10, flexShrink: 0 }} />,
        color: '#d48806',
      };
    }
    // [子任务完成] / [Subtask completed] — must check before subtask started
    if (bracketMatch(msg, '子任务完成', 'Subtask completed')) {
      return {
        icon: <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 10, flexShrink: 0 }} />,
        color: '#52c41a',
      };
    }
    // [子任务进度] / [Subtask progress]
    if (bracketMatch(msg, '子任务进度', 'Subtask progress')) {
      return {
        icon: <SyncOutlined spin={isLatest} style={{ color: '#999', fontSize: 10, flexShrink: 0 }} />,
        color: '#999',
      };
    }
    // [子任务: ...] / [Subtask: ...]
    if (bracketMatch(msg, '子任务', 'Subtask')) {
      return {
        icon: <RobotOutlined style={{ color: '#1677ff', fontSize: 10, flexShrink: 0 }} />,
        color: '#1677ff',
      };
    }
    // Phase 2: [计划已确认] / [Plan confirmed]
    if (bracketMatch(msg, '计划已确认', 'Plan confirmed')) {
      return {
        icon: <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 10, flexShrink: 0 }} />,
        color: '#52c41a',
      };
    }
    // Phase 2: [计划已拒绝] / [Plan rejected]
    if (bracketMatch(msg, '计划已拒绝', 'Plan rejected')) {
      return {
        icon: <StopOutlined style={{ color: '#ff4d4f', fontSize: 10, flexShrink: 0 }} />,
        color: '#ff4d4f',
      };
    }
    // Phase 2: [步骤重试] / [Step retry]
    if (bracketMatch(msg, '步骤重试', 'Step retry')) {
      return {
        icon: <ReloadOutlined style={{ color: '#fa8c16', fontSize: 10, flexShrink: 0 }} />,
        color: '#fa8c16',
      };
    }
    // Phase 2: [开始审查] / [Review started]
    if (bracketMatch(msg, '开始审查', 'Review started')) {
      return {
        icon: <AuditOutlined style={{ color: '#1677ff', fontSize: 10, flexShrink: 0 }} />,
        color: '#1677ff',
      };
    }
    // Phase 2: [审查完成] / [Review complete]
    if (bracketMatch(msg, '审查完成', 'Review complete')) {
      const pass = msg.includes('PASS') || msg.includes('通过');
      return {
        icon: <AuditOutlined style={{ color: pass ? '#52c41a' : '#ff4d4f', fontSize: 10, flexShrink: 0 }} />,
        color: pass ? '#52c41a' : '#ff4d4f',
      };
    }
    // Phase 2: [并行任务开始] / [Parallel tasks started]
    if (bracketMatch(msg, '并行任务开始', 'Parallel tasks started')) {
      return {
        icon: <ThunderboltOutlined style={{ color: '#1677ff', fontSize: 10, flexShrink: 0 }} />,
        color: '#1677ff',
      };
    }
    // Phase 2: [并行任务完成] / [Parallel tasks complete]
    if (bracketMatch(msg, '并行任务完成', 'Parallel tasks complete')) {
      return {
        icon: <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 10, flexShrink: 0 }} />,
        color: '#52c41a',
      };
    }
    // Default
    return {
      icon: isLatest
        ? <SyncOutlined spin style={{ color: '#1677ff', fontSize: 10, flexShrink: 0 }} />
        : <CheckCircleOutlined style={{ color: '#d9d9d9', fontSize: 10, flexShrink: 0 }} />,
      color: isLatest ? '#666' : '#bbb',
    };
  };

  return (
    <div
      ref={containerRef}
      style={{
        maxHeight: 160,
        overflowY: 'auto',
        marginBottom: 10,
        padding: '6px 8px',
        background: 'rgba(0,0,0,0.02)',
        borderRadius: 6,
        borderLeft: '3px solid #d9d9d9',
      }}
    >
      {messages.map((msg, i) => {
        const isLatest = i === messages.length - 1;
        const style = getEntryStyle(msg, isLatest);
        return (
          <div key={i} style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            padding: '2px 0',
            fontSize: 12,
            borderBottom: style.borderBottom || 'none',
            marginBottom: style.borderBottom ? 4 : 0,
            paddingBottom: style.borderBottom ? 4 : 2,
          }}>
            {style.icon}
            <span style={{
              color: style.color,
              fontStyle: style.fontWeight ? 'normal' : 'italic',
              fontWeight: style.fontWeight || 'normal',
            }}>{msg}</span>
          </div>
        );
      })}
    </div>
  );
};

export const AgentStatusHeader: React.FC<{ agent: string; stepDescription: string }> = ({ agent, stepDescription }) => {
  if (!agent) return null;
  const isExecutor = agent === 'executor';
  return (
    <div style={{
      display: 'flex',
      alignItems: 'center',
      gap: 8,
      marginBottom: 10,
      padding: '6px 10px',
      background: isExecutor ? 'rgba(250,173,20,0.06)' : 'rgba(22,119,255,0.06)',
      borderRadius: 6,
      border: `1px solid ${isExecutor ? 'rgba(250,173,20,0.2)' : 'rgba(22,119,255,0.2)'}`,
    }}>
      {isExecutor ? (
        <ThunderboltOutlined style={{ color: '#faad14', fontSize: 14 }} />
      ) : (
        <RobotOutlined style={{ color: '#1677ff', fontSize: 14 }} />
      )}
      <span style={{
        fontSize: 12,
        fontFamily: "'SFMono-Regular', Consolas, monospace",
        fontWeight: 600,
        color: isExecutor ? '#d48806' : '#1677ff',
      }}>
        {agent}
      </span>
      {stepDescription && (
        <>
          <span style={{ color: '#d9d9d9' }}>|</span>
          <span style={{ fontSize: 12, color: '#999' }}>{stepDescription}</span>
        </>
      )}
      <SyncOutlined spin style={{ color: '#999', fontSize: 11, marginLeft: 'auto' }} />
    </div>
  );
};

export const ApprovalCard: React.FC<{
  approval: ApprovalRequest;
  onApprove: (taskId: string) => void;
  onReject: (taskId: string) => void;
  loading: boolean;
}> = ({ approval, onApprove, onReject, loading }) => {
  const { t } = useTranslation();
  return (
    <div style={{
      border: '1px solid #faad14',
      borderRadius: 8,
      padding: 16,
      marginBottom: 12,
      background: 'rgba(250, 173, 20, 0.06)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
        <ExclamationCircleOutlined style={{ color: '#faad14', fontSize: 18 }} />
        <span style={{ fontWeight: 600, fontSize: 14 }}>{t('components.approval.needConfirm')}</span>
      </div>
      <div style={{ fontSize: 13, color: '#666', marginBottom: 12 }}>
        {approval.description}
      </div>
      <div style={{ fontSize: 12, color: '#999', marginBottom: 12 }}>
        {t('components.approval.taskId')} <code style={{ background: 'rgba(0,0,0,0.06)', padding: '1px 4px', borderRadius: 2 }}>{approval.taskId}</code>
      </div>
      {approval.status === 'pending' ? (
        <div style={{ display: 'flex', gap: 8 }}>
          <Button
            type="primary"
            icon={<CheckCircleOutlined />}
            onClick={() => onApprove(approval.taskId)}
            loading={loading}
            style={{ background: '#52c41a', borderColor: '#52c41a' }}
          >
            {t('components.approval.confirmExecute')}
          </Button>
          <Button
            danger
            icon={<StopOutlined />}
            onClick={() => onReject(approval.taskId)}
            loading={loading}
          >
            {t('components.approval.cancelExecute')}
          </Button>
        </div>
      ) : (
        <div style={{
          fontSize: 13,
          fontWeight: 500,
          color: approval.status === 'approved' ? '#52c41a' : '#ff4d4f',
        }}>
          {approval.status === 'approved' ? `✅ ${t('components.approval.approved')}` : `❌ ${t('components.approval.rejected')}`}
        </div>
      )}
    </div>
  );
};
