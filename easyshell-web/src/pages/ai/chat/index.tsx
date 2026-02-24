import React, { useState } from 'react';
import { Drawer, Button } from 'antd';
import { MenuOutlined } from '@ant-design/icons';
import { useAiChat } from '../../../hooks/useAiChat';
import { useResponsive } from '../../../hooks/useResponsive';
import ChatSidebar from './ChatSidebar';
import ChatMessages from './ChatMessages';
import ChatInput from './ChatInput';
import ProcessModal from './ProcessModal';

const AiChat: React.FC = () => {
  const chat = useAiChat();
  const { isMobile } = useResponsive();
  const [sidebarVisible, setSidebarVisible] = useState(false);

  const handleSelectSession = (id: string) => {
    chat.handleSelectSession(id);
    if (isMobile) {
      setSidebarVisible(false);
    }
  };

  return (
    <div className="ai-chat-container">
      {isMobile ? (
        <Drawer
          placement="left"
          open={sidebarVisible}
          onClose={() => setSidebarVisible(false)}
          width={280}
          styles={{ body: { padding: 0 } }}
        >
          <ChatSidebar
            sessions={chat.sessions}
            currentSessionId={chat.currentSessionId}
            loadingSessions={chat.loadingSessions}
            onSelectSession={handleSelectSession}
            onNewChat={() => { chat.handleNewChat(); setSidebarVisible(false); }}
            onDeleteSession={chat.handleDeleteSession}
          />
        </Drawer>
      ) : (
        <ChatSidebar
          sessions={chat.sessions}
          currentSessionId={chat.currentSessionId}
          loadingSessions={chat.loadingSessions}
          onSelectSession={chat.handleSelectSession}
          onNewChat={chat.handleNewChat}
          onDeleteSession={chat.handleDeleteSession}
        />
      )}

      <div className="ai-chat-main" style={{ background: chat.token.colorBgLayout }}>
        {isMobile && (
          <div className="ai-chat-mobile-header" style={{
            padding: '8px 16px',
            borderBottom: `1px solid ${chat.token.colorBorderSecondary}`,
            display: 'flex',
            alignItems: 'center',
            gap: 12,
            background: chat.token.colorBgContainer,
          }}>
            <Button
              type="text"
              icon={<MenuOutlined />}
              onClick={() => setSidebarVisible(true)}
            />
            <span style={{ 
              fontSize: 14, 
              fontWeight: 500,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}>
              {chat.sessions.find(s => s.id === chat.currentSessionId)?.title || 'AI Chat'}
            </span>
          </div>
        )}
        <ChatMessages
          messages={chat.messages}
          loading={chat.loading}
          hoveredMsgId={chat.hoveredMsgId}
          setHoveredMsgId={chat.setHoveredMsgId}
          processDataMap={chat.processDataMap}
          streamingContent={chat.streamingContent}
          streamingPlan={chat.streamingPlan}
          streamingStepIndex={chat.streamingStepIndex}
          streamingThinkingLog={chat.streamingThinkingLog}
          streamingAgent={chat.streamingAgent}
          streamingStepDescription={chat.streamingStepDescription}
          streamingToolCalls={chat.streamingToolCalls}
          isStreaming={chat.isStreaming}
          pendingApprovals={chat.pendingApprovals}
          approvalLoading={chat.approvalLoading}
          planConfirmationStatus={chat.planConfirmationStatus}
          reviewResult={chat.reviewResult}
          parallelProgress={chat.parallelProgress}
          planConfirmLoading={chat.planConfirmLoading}
          currentSessionId={chat.currentSessionId || ''}
          messagesEndRef={chat.messagesEndRef}
          userScrolledUp={chat.userScrolledUp}
          onMessagesScroll={chat.handleMessagesScroll}
          onForceScrollToBottom={chat.forceScrollToBottom}
          onCopyMessage={chat.handleCopyMessage}
          onRetry={chat.handleRetry}
          onSuggestedPrompt={chat.handleSuggestedPrompt}
          onViewProcess={(msgId) => {
            chat.setViewingProcessMsgId(msgId);
            chat.setProcessModalVisible(true);
          }}
          onApprove={chat.handleApprove}
          onReject={chat.handleReject}
          onConfirmPlan={chat.handleConfirmPlan}
          onRejectPlan={chat.handleRejectPlan}
          checkpointSteps={chat.checkpointSteps}
          onApproveCheckpoint={chat.handleApproveCheckpoint}
          onRejectCheckpoint={chat.handleRejectCheckpoint}
          checkpointLoading={chat.checkpointLoading}
        />

        <ChatInput
          provider={chat.provider}
          model={chat.model}
          enableTools={chat.enableTools}
          inputValue={chat.inputValue}
          loading={chat.loading}
          isStreaming={chat.isStreaming}
          targetTreeData={chat.targetTreeData}
          selectedTargetIds={chat.selectedTargetIds}
          loadingTargets={chat.loadingTargets}
          inputRef={chat.inputRef}
          onProviderChange={chat.handleProviderChange}
          onModelChange={chat.setModel}
          onEnableToolsChange={chat.setEnableTools}
          onInputChange={chat.setInputValue}
          onSelectedTargetIdsChange={chat.setSelectedTargetIds}
          onSend={chat.handleSend}
          onStop={chat.handleStop}
          onKeyDown={chat.handleKeyDown}
        />
      </div>

      <ProcessModal
        visible={chat.processModalVisible}
        onClose={() => {
          chat.setProcessModalVisible(false);
          chat.setViewingProcessMsgId(null);
        }}
        processData={chat.viewingProcessMsgId !== null ? chat.processDataMap[chat.viewingProcessMsgId] || null : null}
      />
    </div>
  );
};

export default AiChat;
