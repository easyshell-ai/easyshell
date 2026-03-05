import React, { useState, useCallback } from 'react';
import { Button, Tooltip, theme } from 'antd';
import {
  RobotOutlined,
  UserOutlined,
  LoadingOutlined,
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
  streamingToolCalls, isStreaming: _isStreaming, pendingApprovals, approvalLoading,
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
        <div style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          height: '100%',
          color: token.colorTextSecondary,
        }}>
          <RobotOutlined style={{ fontSize: 48, marginBottom: 16, opacity: 0.3 }} />
          <div style={{ fontSize: 18, fontWeight: 600, marginBottom: 8, color: token.colorText }}>{t('chat.aiAssistant')}</div>
          <div style={{ fontSize: 14, marginBottom: 24 }}>{t('chat.aiDescription')}</div>
          <div style={{
            display: 'grid',
            gridTemplateColumns: isMobile ? '1fr' : '1fr 1fr',
            gap: isMobile ? 8 : 12,
            maxWidth: isMobile ? 320 : 480,
            width: '100%',
            padding: isMobile ? '0 16px' : 0,
          }}>
            {SUGGESTED_PROMPTS.map((item, idx) => (
              <div
                key={idx}
                onClick={() => onSuggestedPrompt(t(item.text))}
                style={{
                  padding: '14px 16px',
                  borderRadius: 10,
                  border: `1px solid ${token.colorBorderSecondary}`,
                  background: token.colorBgContainer,
                  cursor: 'pointer',
                  transition: 'all 0.2s',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  fontSize: 14,
                  color: token.colorText,
                }}
                onMouseEnter={(e) => {
                  (e.currentTarget as HTMLDivElement).style.borderColor = token.colorPrimary;
                  (e.currentTarget as HTMLDivElement).style.boxShadow = '0 2px 8px rgba(0,0,0,0.08)';
                }}
                onMouseLeave={(e) => {
                  (e.currentTarget as HTMLDivElement).style.borderColor = token.colorBorderSecondary;
                  (e.currentTarget as HTMLDivElement).style.boxShadow = 'none';
                }}
              >
                <span style={{ fontSize: 20 }}>{item.icon}</span>
                <span>{t(item.text)}</span>
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
                  width: 32,
                  height: 32,
                  borderRadius: 16,
                  background: token.colorPrimary,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  marginRight: 8,
                  flexShrink: 0,
                }}>
                  <RobotOutlined style={{ color: '#fff', fontSize: 16 }} />
                </div>
              )}
              <div style={{ maxWidth: isMobile ? '85%' : '70%', position: 'relative', minWidth: 0 }}>
                <div style={{
                  padding: '10px 16px',
                  borderRadius: msg.role === 'user' ? '16px 16px 4px 16px' : '16px 16px 16px 4px',
                  background: msg.role === 'user' ? token.colorPrimary : token.colorBgContainer,
                  color: msg.role === 'user' ? '#fff' : token.colorText,
                  fontSize: 14,
                  lineHeight: 1.6,
                  boxShadow: '0 1px 2px rgba(0,0,0,0.06)',
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
                  width: 32,
                  height: 32,
                  borderRadius: 16,
                  background: token.colorPrimaryBg,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  marginLeft: 8,
                  flexShrink: 0,
                }}>
                  <UserOutlined style={{ color: token.colorPrimary, fontSize: 16 }} />
                </div>
              )}
            </div>
          ))}

          {loading && (
            <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 16 }}>
              <div style={{
                width: 32,
                height: 32,
                borderRadius: 16,
                background: token.colorPrimary,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                marginRight: 8,
                flexShrink: 0,
              }}>
                <RobotOutlined style={{ color: '#fff', fontSize: 16 }} />
              </div>
              <div style={{
                padding: '10px 16px',
                borderRadius: '16px 16px 16px 4px',
                background: token.colorBgContainer,
                fontSize: 14,
                lineHeight: 1.6,
                boxShadow: '0 1px 2px rgba(0,0,0,0.06)',
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
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <LoadingOutlined spin />
                      <span style={{ color: token.colorTextSecondary }}>{t('ai.chat.thinking')}</span>
                    </div>
                  )
                )}
              </div>
            </div>
          )}

          {!loading && pendingApprovals.length > 0 && (
            <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 16 }}>
              <div style={{
                width: 32,
                height: 32,
                borderRadius: 16,
                background: token.colorPrimary,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                marginRight: 8,
                flexShrink: 0,
              }}>
                <RobotOutlined style={{ color: '#fff', fontSize: 16 }} />
              </div>
              <div style={{
                padding: '10px 16px',
                borderRadius: '16px 16px 16px 4px',
                background: token.colorBgContainer,
                fontSize: 14,
                lineHeight: 1.6,
                boxShadow: '0 1px 2px rgba(0,0,0,0.06)',
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
