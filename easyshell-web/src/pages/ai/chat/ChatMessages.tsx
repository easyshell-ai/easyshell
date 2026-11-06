import React, { useState, useCallback } from 'react';
import { Button, Tooltip, theme } from 'antd';
import {
  RobotOutlined,
  UserOutlined,
  CopyOutlined,
  CheckOutlined,
  ReloadOutlined,
  EyeOutlined,
  ArrowDownOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useResponsive } from '../../../hooks/useResponsive';
import MarkdownContent from '../../../components/MarkdownContent';
import { ExecutionPlanView, ToolCallView, ThinkingLog, AgentStatusHeader, ApprovalCard } from './components';
import PlanCard from './PlanCard';
import ParallelTaskPanel from './ParallelTaskPanel';
import ReviewResultCard from './ReviewResultCard';
import { SUGGESTED_PROMPTS } from './constants';
import type { AiChatMessage, ExecutionPlan } from '../../../types';
import type { ApprovalRequest, ProcessData } from './types';

interface ChatMessagesProps {
  messages: AiChatMessage[];
  loading: boolean;
  processDataMap: Record<number, ProcessData>;
  streamingContent: string;
  streamingPlan: ExecutionPlan | null;
  streamingStepIndex: number;
  streamingThinkingLog: string[];
  streamingAgent: string;
  streamingStepDescription: string;
  streamingToolCalls: Array<{ toolName: string; toolArgs: string; toolResult?: string }>;
  isStreaming: boolean;
  pendingApprovals: ApprovalRequest[];
  approvalLoading: boolean;
  planConfirmationStatus?: 'awaiting' | 'confirmed' | 'rejected';
  reviewResult?: string;
  parallelProgress: Array<{ group: number; completed: number; total: number }>;
  planConfirmLoading?: boolean;
  currentSessionId: string;
  messagesEndRef: React.RefObject<HTMLDivElement | null>;
  userScrolledUp: boolean;
  onMessagesScroll: (e: React.UIEvent<HTMLDivElement>) => void;
  onForceScrollToBottom: () => void;
  onCopyMessage: (content: string) => void;
  onRetry: () => void;
  onSuggestedPrompt: (text: string) => void;
  onViewProcess: (msgId: number) => void;
  onApprove: (taskId: string) => void;
  onReject: (taskId: string) => void;
  onConfirmPlan: () => void;
  onRejectPlan: () => void;
  checkpointSteps: Set<number>;
  onApproveCheckpoint: (stepIndex: number) => void;
  onRejectCheckpoint: (stepIndex: number) => void;
  checkpointLoading?: number | null;
}

const ChatMessages: React.FC<ChatMessagesProps> = ({
  messages, loading,
  processDataMap, streamingContent, streamingPlan, streamingStepIndex,
  streamingThinkingLog, streamingAgent, streamingStepDescription,
  streamingToolCalls, pendingApprovals, approvalLoading,
  planConfirmationStatus, reviewResult, parallelProgress, planConfirmLoading,
  currentSessionId,
  messagesEndRef, userScrolledUp, onMessagesScroll, onForceScrollToBottom,
  onCopyMessage, onRetry, onSuggestedPrompt,
  onViewProcess, onApprove, onReject, onConfirmPlan, onRejectPlan,
  checkpointSteps, onApproveCheckpoint, onRejectCheckpoint, checkpointLoading,
}) => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const { isMobile } = useResponsive();
  const [copiedMsgId, setCopiedMsgId] = useState<number | null>(null);

  const handleCopy = useCallback((msgId: number, content: string) => {
    onCopyMessage(content);
    setCopiedMsgId(msgId);
    setTimeout(() => setCopiedMsgId(null), 2000);
  }, [onCopyMessage]);

  return (
    <div style={{ flex: 1, position: 'relative', overflow: 'hidden', minHeight: 0 }}>
      <div className="ai-chat-messages" onScroll={onMessagesScroll}>
      {messages.length === 0 && !loading ? (
        <div className="ai-chat-empty-state" style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          height: '100%',
          color: token.colorTextSecondary,
        }}>
          <RobotOutlined className="ai-chat-empty-icon" style={{ fontSize: 64, marginBottom: 24 }} />
          <div style={{ 
            fontSize: 24, 
            fontWeight: 700, 
            marginBottom: 8, 
            background: `linear-gradient(90deg, ${token.colorPrimary}, #8b5cf6)`,
            WebkitBackgroundClip: 'text',
            WebkitTextFillColor: 'transparent',
            letterSpacing: '-0.5px'
          }}>
            {t('chat.aiAssistant')}
          </div>
          <div style={{ fontSize: 15, marginBottom: 32, opacity: 0.8 }}>{t('chat.aiDescription')}</div>
          <div style={{
            display: 'grid',
            gridTemplateColumns: isMobile ? '1fr' : '1fr 1fr',
            gap: isMobile ? 12 : 16,
            maxWidth: isMobile ? 320 : 520,
            width: '100%',
            padding: isMobile ? '0 16px' : 0,
          }}>
            {SUGGESTED_PROMPTS.map((item, idx) => (
              <div
                key={idx}
                className="suggested-prompt-card"
                onClick={() => onSuggestedPrompt(t(item.text))}
                style={{
                  padding: '16px 20px',
                  borderRadius: 12,
                  border: `1px solid ${token.colorBorderSecondary}`,
                  borderLeft: `1px solid ${token.colorBorderSecondary}`,
                  background: token.colorBgContainer,
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 12,
                  fontSize: 14,
                  color: token.colorText,
                  boxShadow: '0 2px 4px rgba(0,0,0,0.02)',
                }}
                onMouseEnter={(e) => {
                  (e.currentTarget as HTMLDivElement).style.borderColor = token.colorPrimary;
                  (e.currentTarget as HTMLDivElement).style.borderLeft = `4px solid ${token.colorPrimary}`;
                  (e.currentTarget as HTMLDivElement).style.boxShadow = '0 4px 12px rgba(0,0,0,0.08)';
                }}
                onMouseLeave={(e) => {
                  (e.currentTarget as HTMLDivElement).style.borderColor = token.colorBorderSecondary;
                  (e.currentTarget as HTMLDivElement).style.borderLeft = `1px solid ${token.colorBorderSecondary}`;
                  (e.currentTarget as HTMLDivElement).style.boxShadow = '0 2px 4px rgba(0,0,0,0.02)';
                }}
              >
                <span style={{ fontSize: 22 }}>{item.icon}</span>
                <span style={{ fontWeight: 500 }}>{t(item.text)}</span>
              </div>
            ))}
          </div>
        </div>
      ) : (
        <>
          {messages.map((msg) => (
            <div
              key={msg.id}
              className="msg-row"
              style={{
                display: 'flex',
                justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start',
                marginBottom: 16,
                position: 'relative',
              }}
            >
              {msg.role !== 'user' && (
                <div style={{
                  width: 36,
                  height: 36,
                  borderRadius: 18,
                  background: `linear-gradient(135deg, ${token.colorPrimary}, #8b5cf6)`,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  marginRight: 12,
                  flexShrink: 0,
                  boxShadow: '0 2px 8px rgba(0,0,0,0.15)',
                }}>
                  <RobotOutlined style={{ color: '#fff', fontSize: 18 }} />
                </div>
              )}
              <div style={{ maxWidth: isMobile ? '85%' : '70%', position: 'relative', minWidth: 0 }}>
                <div style={{
                  padding: msg.role === 'user' ? '12px 18px' : '12px 18px',
                  borderRadius: msg.role === 'user' ? '20px 20px 4px 20px' : '20px 20px 20px 4px',
                  background: msg.role === 'user' ? `linear-gradient(135deg, ${token.colorPrimary}, #60a5fa)` : token.colorBgContainer,
                  color: msg.role === 'user' ? '#fff' : token.colorText,
                  fontSize: 15,
                  lineHeight: 1.6,
                  boxShadow: msg.role === 'user' ? '0 4px 12px rgba(37, 99, 235, 0.2)' : '0 2px 8px rgba(0,0,0,0.08)',
                  borderLeft: msg.role === 'user' ? 'none' : `3px solid ${token.colorPrimary}40`,
                  wordBreak: 'break-word',
                }}>
                  {msg.role === 'user' ? (
                    <span style={{ whiteSpace: 'pre-wrap', userSelect: 'text', cursor: 'text' }}>{msg.content}</span>
                  ) : (
                    <MarkdownContent content={msg.content} />
                  )}
                </div>
                {msg.role === 'assistant' && (
                  <div className={`msg-actions${processDataMap[msg.id] ? ' has-process' : ''}`} style={{ display: 'flex', gap: 4, marginTop: 4 }}>
                    {processDataMap[msg.id] && (
                      <Tooltip title={t('chat.viewProcess')}>
                        <Button
                          type="text"
                          size="small"
                          icon={<EyeOutlined />}
                          onClick={() => onViewProcess(msg.id)}
                          style={{ color: token.colorPrimary, fontSize: 12 }}
                        />
                      </Tooltip>
                    )}
                    <span className="msg-hover-btns">
                      <Tooltip title={copiedMsgId === msg.id ? t('common.copied') : t('chat.copy')}>
                        <Button
                          type="text"
                          size="small"
                          icon={copiedMsgId === msg.id ? <CheckOutlined style={{ color: '#52c41a' }} /> : <CopyOutlined />}
                          onClick={() => handleCopy(msg.id, msg.content)}
                          style={{ color: copiedMsgId === msg.id ? '#52c41a' : token.colorTextTertiary, fontSize: 12 }}
                        />
                      </Tooltip>
                      <Tooltip title={t('chat.regenerate')}>
                        <Button
                          type="text"
                          size="small"
                          icon={<ReloadOutlined />}
                          onClick={onRetry}
                          style={{ color: token.colorTextTertiary, fontSize: 12 }}
                        />
                      </Tooltip>
                    </span>
                  </div>
                )}
              </div>
              {msg.role === 'user' && (
                <div style={{
                  width: 36,
                  height: 36,
                  borderRadius: 18,
                  background: token.colorBgContainer,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  marginLeft: 12,
                  flexShrink: 0,
                  boxShadow: '0 2px 8px rgba(0,0,0,0.1)',
                  border: `1px solid ${token.colorBorderSecondary}`,
                }}>
                  <UserOutlined style={{ color: token.colorPrimary, fontSize: 18 }} />
                </div>
              )}
            </div>
          ))}

          {loading && (
            <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 16 }}>
              <div style={{
                width: 36,
                height: 36,
                borderRadius: 18,
                background: `linear-gradient(135deg, ${token.colorPrimary}, #8b5cf6)`,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                marginRight: 12,
                flexShrink: 0,
                boxShadow: '0 2px 8px rgba(0,0,0,0.15)',
              }}>
                <RobotOutlined style={{ color: '#fff', fontSize: 18 }} />
              </div>
              <div style={{
                padding: '12px 18px',
                borderRadius: '20px 20px 20px 4px',
                background: token.colorBgContainer,
                fontSize: 15,
                lineHeight: 1.6,
                boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                borderLeft: `3px solid ${token.colorPrimary}40`,
                maxWidth: isMobile ? '85%' : '70%',
                minWidth: isMobile ? 160 : 200,
                overflow: 'hidden',
              }}>
                {streamingPlan && (
                  streamingPlan.requiresConfirmation ? (
                    <PlanCard
                      plan={streamingPlan}
                      currentStepIndex={streamingStepIndex}
                      sessionId={currentSessionId}
                      confirmationStatus={planConfirmationStatus}
                      onConfirm={onConfirmPlan}
                      onReject={onRejectPlan}
                      confirmLoading={planConfirmLoading}
                      checkpointSteps={checkpointSteps}
                      onApproveCheckpoint={onApproveCheckpoint}
                      onRejectCheckpoint={onRejectCheckpoint}
                      checkpointLoading={checkpointLoading}
                    />
                  ) : (
                    <ExecutionPlanView plan={streamingPlan} currentStepIndex={streamingStepIndex} />
                  )
                )}
                <AgentStatusHeader agent={streamingAgent} stepDescription={streamingStepDescription} />
                <ThinkingLog messages={streamingThinkingLog} />
                {streamingToolCalls.map((tc, i) => (
                  <ToolCallView key={i} toolName={tc.toolName} toolArgs={tc.toolArgs} toolResult={tc.toolResult} />
                ))}
                {parallelProgress.length > 0 && (
                  <ParallelTaskPanel groups={parallelProgress} />
                )}
                {reviewResult && (
                  <ReviewResultCard content={reviewResult} />
                )}
                {pendingApprovals.map((approval) => (
                  <ApprovalCard
                    key={approval.taskId}
                    approval={approval}
                    onApprove={onApprove}
                    onReject={onReject}
                    loading={approvalLoading}
                  />
                ))}
                {streamingContent ? (
                  <MarkdownContent content={streamingContent} />
                ) : (
                  !streamingPlan && streamingThinkingLog.length === 0 && (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '4px 0' }}>
                      <span style={{ 
                        color: token.colorPrimary, 
                        fontWeight: 500, 
                        fontSize: 14,
                        letterSpacing: '0.3px'
                      }}>
                        {t('ai.chat.thinking')}
                      </span>
                      <div className="typing-dots">
                        <span></span>
                        <span></span>
                        <span></span>
                      </div>
                    </div>
                  )
                )}
              </div>
            </div>
          )}

          {!loading && pendingApprovals.length > 0 && (
            <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 16 }}>
              <div style={{
                width: 36,
                height: 36,
                borderRadius: 18,
                background: `linear-gradient(135deg, ${token.colorPrimary}, #8b5cf6)`,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                marginRight: 12,
                flexShrink: 0,
                boxShadow: '0 2px 8px rgba(0,0,0,0.15)',
              }}>
                <RobotOutlined style={{ color: '#fff', fontSize: 18 }} />
              </div>
              <div style={{
                padding: '12px 18px',
                borderRadius: '20px 20px 20px 4px',
                background: token.colorBgContainer,
                fontSize: 15,
                lineHeight: 1.6,
                boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                borderLeft: `3px solid ${token.colorPrimary}40`,
                maxWidth: isMobile ? '85%' : '70%',
                minWidth: isMobile ? 160 : 200,
              }}>
                {pendingApprovals.map((approval) => (
                  <ApprovalCard
                    key={approval.taskId}
                    approval={approval}
                    onApprove={onApprove}
                    onReject={onReject}
                    loading={approvalLoading}
                  />
                ))}
              </div>
            </div>
          )}

          <div ref={messagesEndRef} />
        </>
      )}
      </div>

      {userScrolledUp && (
        <Button
          type="primary"
          shape="circle"
          icon={<ArrowDownOutlined />}
          size="middle"
          onClick={onForceScrollToBottom}
          style={{
            position: 'absolute',
            bottom: 16,
            right: 24,
            zIndex: 10,
            boxShadow: '0 2px 8px rgba(0,0,0,0.2)',
          }}
        />
      )}
    </div>
  );
};

export default ChatMessages;
