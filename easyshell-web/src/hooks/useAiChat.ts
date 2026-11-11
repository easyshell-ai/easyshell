import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { Input, message, theme } from 'antd';
import { useTranslation } from 'react-i18next';
import { getChatSessions, getChatMessages, deleteChatSession, sendChatStream, approveExecution, rejectExecution, saveProcessData, confirmPlan, rejectPlan, approveCheckpoint, rejectCheckpoint } from '../api/ai';
import { getClusterList, getClusterDetail } from '../api/cluster';
import { getHostList } from '../api/host';
import { getSessionEpoch, subscribeSessionEpoch } from '../api/request';
import type { AiChatSession, AiChatMessage, ClusterDetailVO, Agent, AgentEvent, ExecutionPlan } from '../types';
import type { ApprovalRequest, ProcessData, ActiveStreamState, CompletedStreamResult, TreeNode } from '../pages/ai/chat/types';

// Module-level mutable state — survives React navigation (unmount/remount).
// These are intentionally NOT React state.
let activeStream: ActiveStreamState | null = null;
let completedStreamResult: CompletedStreamResult | null = null;
let completedStreamOwner: ActiveStreamState | null = null;
let latestStream: ActiveStreamState | null = null;
const pendingSessionDeletes = new Map<object, { sessionId: string; done: Promise<void> }>();
const deletedSessions = new Set<string>();
const deleteSubscribers = new Set<(sessionId: string) => void>();
const streamSubscribers = new WeakMap<ActiveStreamState, Set<() => void>>();
function notifyStream(stream: ActiveStreamState) {
  streamSubscribers.get(stream)?.forEach(listener => listener());
}
const streamCheckpoints = new WeakMap<ActiveStreamState, Set<number>>();
const streamResults = new WeakMap<ActiveStreamState, CompletedStreamResult>();
subscribeSessionEpoch(() => {
  const previous = activeStream;
  activeStream = null;
  completedStreamResult = null;
  completedStreamOwner = null;
  latestStream = null;
  pendingSessionDeletes.clear();
  deletedSessions.clear();
  if (previous) {
    streamSubscribers.delete(previous);
    previous.controller?.abort();
  }
});

export function useAiChat() {
  const { token } = theme.useToken();
  const { t } = useTranslation();

  const [sessions, setSessions] = useState<AiChatSession[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [messages, setMessagesState] = useState<AiChatMessage[]>([]);
  const messagesRef = useRef(messages);
  const setMessages = useCallback((next: AiChatMessage[]) => {
    messagesRef.current = next;
    setMessagesState(next);
  }, []);
  const [inputValue, setInputValue] = useState('');
  const [loading, setLoading] = useState(false);
  const [provider, setProvider] = useState('default');
  const [model, setModel] = useState('');
  const [enableTools, setEnableTools] = useState(true);
  const [loadingSessions, setLoadingSessions] = useState(false);
  const [sessionEpoch, setSessionEpoch] = useState(getSessionEpoch);
  const [hoveredMsgId, setHoveredMsgId] = useState<number | null>(null);

  const [selectedTargetIds, setSelectedTargetIds] = useState<string[]>([]);
  const [clusters, setClusters] = useState<ClusterDetailVO[]>([]);
  const [hosts, setHosts] = useState<Agent[]>([]);
  const [loadingTargets, setLoadingTargets] = useState(false);

  const [streamingContent, setStreamingContent] = useState('');
  const [streamingPlan, setStreamingPlan] = useState<ExecutionPlan | null>(null);
  const [streamingStepIndex, setStreamingStepIndex] = useState(-1);
  const [streamingThinkingLog, setStreamingThinkingLog] = useState<string[]>([]);
  const [streamingAgent, setStreamingAgent] = useState('');
  const [streamingStepDescription, setStreamingStepDescription] = useState('');
  const [streamingToolCalls, setStreamingToolCalls] = useState<Array<{ toolName: string; toolArgs: string; toolResult?: string }>>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [pendingApprovals, setPendingApprovals] = useState<ApprovalRequest[]>([]);
  const [approvalLoading, setApprovalLoading] = useState(false);
  const [planConfirmationStatus, setPlanConfirmationStatus] = useState<'awaiting' | 'confirmed' | 'rejected' | undefined>();
  const [planId, setPlanId] = useState<string | undefined>();
  const [reviewResult, setReviewResult] = useState<string | undefined>();
  const [parallelProgress, setParallelProgress] = useState<Array<{ group: number; completed: number; total: number }>>([]);
  const [planConfirmLoading, setPlanConfirmLoading] = useState(false);
  const [checkpointSteps, setCheckpointSteps] = useState<Set<number>>(new Set());
  const [checkpointLoading, setCheckpointLoading] = useState<number | null>(null);
  const [processDataMap, setProcessDataMap] = useState<Record<number, ProcessData>>({});
  const [processModalVisible, setProcessModalVisible] = useState(false);
  const [viewingProcessMsgId, setViewingProcessMsgId] = useState<number | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const currentSessionIdRef = useRef<string | null>(null);
  const initialSessionsLoadedRef = useRef(false);
  const viewVersionRef = useRef(0);
  const attachedStreamRef = useRef<ActiveStreamState | null>(null);
  const mountedRef = useRef(false);
  const unsubscribeStreamRef = useRef<(() => void) | null>(null);
  const sessionsRequestRef = useRef(0);
  const detachStream = useCallback(() => {
    unsubscribeStreamRef.current?.();
    unsubscribeStreamRef.current = null;
    attachedStreamRef.current = null;
  }, []);

  // Refs to capture current streaming state inside callbacks (closures capture stale state)
  const streamingPlanRef = useRef<ExecutionPlan | null>(null);
  const streamingThinkingLogRef = useRef<string[]>([]);
  const streamingToolCallsRef = useRef<Array<{ toolName: string; toolArgs: string; toolResult?: string }>>([]);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<React.ComponentRef<typeof Input.TextArea>>(null);
  const userScrolledUpRef = useRef(false);
  const [userScrolledUp, setUserScrolledUp] = useState(false);

  const scrollToBottom = useCallback(() => {
    if (userScrolledUpRef.current) return;
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  const forceScrollToBottom = useCallback(() => {
    userScrolledUpRef.current = false;
    setUserScrolledUp(false);
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  const handleMessagesScroll = useCallback((e: React.UIEvent<HTMLDivElement>) => {
    const { scrollTop, scrollHeight, clientHeight } = e.currentTarget;
    const isNearBottom = scrollHeight - scrollTop - clientHeight < 50;
    const wasScrolledUp = userScrolledUpRef.current;
    userScrolledUpRef.current = !isNearBottom;
    if (wasScrolledUp !== !isNearBottom) {
      setUserScrolledUp(!isNearBottom);
    }
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, scrollToBottom]);

  useEffect(() => { streamingPlanRef.current = streamingPlan; }, [streamingPlan]);
  useEffect(() => { streamingThinkingLogRef.current = streamingThinkingLog; }, [streamingThinkingLog]);
  useEffect(() => { streamingToolCallsRef.current = streamingToolCalls; }, [streamingToolCalls]);
  useEffect(() => { currentSessionIdRef.current = currentSessionId; }, [currentSessionId]);

  useEffect(() => {
    if (!isStreaming) return;
    const id = setInterval(() => {
      if (!userScrolledUpRef.current) {
        messagesEndRef.current?.scrollIntoView({ behavior: 'auto' });
      }
    }, 300);
    return () => clearInterval(id);
  }, [isStreaming]);

  const attachToActiveStream = useCallback(() => {
    if (!activeStream) return;
    detachStream();
    const stream = activeStream;
    attachedStreamRef.current = stream;
    setStreamingContent(activeStream.content);
    setStreamingPlan(activeStream.plan);
    setStreamingStepIndex(activeStream.stepIndex);
    setStreamingThinkingLog([...activeStream.thinkingLog]);
    setStreamingAgent(activeStream.agent);
    setStreamingStepDescription(activeStream.stepDescription);
    setStreamingToolCalls([...activeStream.toolCalls]);
    setIsStreaming(true);
    setLoading(true);
    setPendingApprovals([...activeStream.pendingApprovals]);
    setCheckpointSteps(new Set(streamCheckpoints.get(activeStream) || []));
    setPlanConfirmationStatus(activeStream.planConfirmationStatus);
    setPlanId(activeStream.planId);
    setReviewResult(activeStream.reviewResult);
    setParallelProgress(activeStream.parallelProgress || []);
    if (activeStream.messages.length > 0) {
      setMessages([...activeStream.messages]);
    }
    abortRef.current = activeStream.controller;
    const listener = () => {
      if (!activeStream || attachedStreamRef.current !== activeStream) return;
      if (!activeStream.isStreaming) {
        const result = streamResults.get(activeStream);
        if (result) {
          setMessages([...result.messages]);
          setProcessDataMap(prev => ({ ...prev, ...result.processDataMap }));
        }
        setLoading(false);
        setIsStreaming(false);
        setStreamingContent('');
        setStreamingPlan(null);
        setStreamingThinkingLog([]);
        setStreamingToolCalls([]);
        setPendingApprovals([]);
        setCheckpointSteps(new Set());
        setApprovalLoading(false);
        setCheckpointLoading(null);
        setPlanConfirmLoading(false);
        setPlanId(undefined);
        setPlanConfirmationStatus(undefined);
        detachStream();
        return;
      }
      if (activeStream.sessionId && currentSessionIdRef.current !== activeStream.sessionId) {
        currentSessionIdRef.current = activeStream.sessionId;
        setCurrentSessionId(activeStream.sessionId);
      }
      setStreamingContent(activeStream.content);
      setStreamingPlan(activeStream.plan);
      setStreamingStepIndex(activeStream.stepIndex);
      setStreamingThinkingLog([...activeStream.thinkingLog]);
      setStreamingAgent(activeStream.agent);
      setStreamingStepDescription(activeStream.stepDescription);
      setStreamingToolCalls([...activeStream.toolCalls]);
      setPendingApprovals([...activeStream.pendingApprovals]);
      setCheckpointSteps(new Set(streamCheckpoints.get(activeStream) || []));
      setPlanConfirmationStatus(activeStream.planConfirmationStatus);
      setPlanId(activeStream.planId);
      setReviewResult(activeStream.reviewResult);
      setParallelProgress(activeStream.parallelProgress || []);
    };
    let subscribers = streamSubscribers.get(stream);
    if (!subscribers) {
      subscribers = new Set();
      streamSubscribers.set(stream, subscribers);
    }
    subscribers.add(listener);
    unsubscribeStreamRef.current = () => subscribers.delete(listener);
  }, [detachStream]);

  useEffect(() => {
    return subscribeSessionEpoch(() => {
      viewVersionRef.current++;
      detachStream();
      sessionsRequestRef.current++;
      setLoadingSessions(false);
      setLoadingTargets(false);
      setSessionEpoch(getSessionEpoch());
      initialSessionsLoadedRef.current = false;
      abortRef.current = null;
      currentSessionIdRef.current = null;
      setCurrentSessionId(null);
      setMessages([]);
      setInputValue('');
      setSessions([]);
      setProcessDataMap({});
      setProcessModalVisible(false);
      setViewingProcessMsgId(null);
      setHoveredMsgId(null);
      setStreamingContent('');
      setStreamingPlan(null);
      setStreamingStepIndex(-1);
      setStreamingThinkingLog([]);
      setStreamingAgent('');
      setStreamingStepDescription('');
      setStreamingToolCalls([]);
      setPendingApprovals([]);
      setPlanId(undefined);
      setPlanConfirmationStatus(undefined);
      setReviewResult(undefined);
      setParallelProgress([]);
      setCheckpointSteps(new Set());
      setClusters([]);
      setHosts([]);
      setSelectedTargetIds([]);
      setLoading(false);
      setIsStreaming(false);
      setApprovalLoading(false);
      setPlanConfirmLoading(false);
      setCheckpointLoading(null);
    });
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    if (activeStream && activeStream.isStreaming) {
      currentSessionIdRef.current = activeStream.sessionId;
      setCurrentSessionId(activeStream.sessionId);
      attachToActiveStream();
    } else if (completedStreamResult) {
      const result = completedStreamResult;
      currentSessionIdRef.current = result.sessionId;
      setCurrentSessionId(result.sessionId);
      setMessages(result.messages);
      setProcessDataMap(prev => ({ ...prev, ...result.processDataMap }));
    }

    return () => {
      mountedRef.current = false;
      viewVersionRef.current++;
      detachStream();
    };
  }, []);

  const loadSessions = useCallback(async () => {
    const epoch = getSessionEpoch();
    const request = ++sessionsRequestRef.current;
    const valid = () => mountedRef.current && epoch === getSessionEpoch() && request === sessionsRequestRef.current;
    if (!initialSessionsLoadedRef.current) {
      setLoadingSessions(true);
    }
    try {
      const res = await getChatSessions();
      if (valid() && res.code === 200) {
        setSessions((res.data || []).filter(session => !deletedSessions.has(session.id)));
      }
    } catch {
      // ignore
    } finally {
      if (valid()) {
        setLoadingSessions(false);
        initialSessionsLoadedRef.current = true;
      }
    }
  }, []);

  useEffect(() => {
    loadSessions();
  }, [loadSessions, sessionEpoch]);

  useEffect(() => {
    const loadTargets = async () => {
      const epoch = getSessionEpoch();
      setLoadingTargets(true);
      try {
        const [clusterRes, hostRes] = await Promise.all([
          getClusterList(),
          getHostList(),
        ]);

        if (epoch !== getSessionEpoch()) return;

        const allHosts = hostRes.code === 200 ? (hostRes.data || []) : [];
        setHosts(allHosts);

        if (clusterRes.code === 200 && clusterRes.data) {
          const detailPromises = clusterRes.data.map(c => getClusterDetail(c.id));
          const details = await Promise.all(detailPromises);
          if (epoch !== getSessionEpoch()) return;
          const clusterDetails = details
            .filter(d => d.code === 200 && d.data)
            .map(d => d.data as ClusterDetailVO);
          setClusters(clusterDetails);
        }
      } catch {
        // ignore
      } finally {
        if (epoch === getSessionEpoch()) setLoadingTargets(false);
      }
    };
    loadTargets();
  }, [sessionEpoch]);

  const targetTreeData = useMemo<TreeNode[]>(() => {
    const clusteredAgentIds = new Set<string>();
    const treeNodes: TreeNode[] = [];

    for (const cluster of clusters) {
      if (cluster.agents && cluster.agents.length > 0) {
        cluster.agents.forEach(a => clusteredAgentIds.add(a.id));
        treeNodes.push({
          title: `${cluster.name} (${cluster.agents.length})`,
          value: `cluster_${cluster.id}`,
          key: `cluster_${cluster.id}`,
          selectable: false,
          children: cluster.agents.map(agent => ({
            title: `${agent.hostname} (${agent.ip})`,
            value: agent.id,
            key: agent.id,
          })),
        });
      }
    }

    const ungrouped = hosts.filter(h => !clusteredAgentIds.has(h.id));
    if (ungrouped.length > 0) {
      treeNodes.push({
        title: t('chat.ungroupedHosts', { count: ungrouped.length }),
        value: 'ungrouped',
        key: 'ungrouped',
        selectable: false,
        children: ungrouped.map(agent => ({
          title: `${agent.hostname} (${agent.ip})`,
          value: agent.id,
          key: agent.id,
        })),
      });
    }

    return treeNodes;
  }, [clusters, hosts]);

  const loadMessages = useCallback(async (sessionId: string) => {
    const epoch = getSessionEpoch();
    const version = viewVersionRef.current;
    const isCurrentView = () => mountedRef.current && epoch === getSessionEpoch() && version === viewVersionRef.current && !deletedSessions.has(sessionId);
    try {
      const res = await getChatMessages(sessionId);
      if (isCurrentView() && res.code === 200) {
        const msgs = res.data || [];
        setMessages(msgs);
        const newProcessDataMap: Record<number, ProcessData> = {};
        for (const msg of msgs) {
          if (msg.role === 'assistant' && msg.processData) {
            try {
              const parsed = JSON.parse(msg.processData) as ProcessData;
              newProcessDataMap[msg.id] = parsed;
            } catch { }
          }
        }
        setProcessDataMap(prev => ({ ...prev, ...newProcessDataMap }));
      }
    } catch {
      if (isCurrentView()) message.error(t('chat.loadMessagesFailed'));
    }
  }, []);

  const resetStreamingUI = useCallback(() => {
    setIsStreaming(false);
    setLoading(false);
    setStreamingContent('');
    setStreamingPlan(null);
    setStreamingStepIndex(-1);
    setStreamingThinkingLog([]);
    setStreamingAgent('');
    setStreamingStepDescription('');
    setStreamingToolCalls([]);
    setPendingApprovals([]);
    setApprovalLoading(false);
    userScrolledUpRef.current = false;
    setUserScrolledUp(false);
    setPlanConfirmationStatus(undefined);
    setPlanId(undefined);
    setReviewResult(undefined);
    setParallelProgress([]);
    setPlanConfirmLoading(false);
    setCheckpointSteps(new Set());
    setCheckpointLoading(null);
  }, []);

  const handleSelectSession = useCallback((sessionId: string) => {
    if (deletedSessions.has(sessionId)) return;
    viewVersionRef.current++;
    currentSessionIdRef.current = sessionId;
    detachStream();
    setMessages([]);
    setProcessDataMap({});
    setApprovalLoading(false);
    setPlanConfirmLoading(false);
    setCheckpointLoading(null);
    if (activeStream && activeStream.sessionId !== sessionId) {
      resetStreamingUI();
    }

    setCurrentSessionId(sessionId);

    if (activeStream && activeStream.sessionId === sessionId) {
      attachToActiveStream();
    } else if (completedStreamResult && completedStreamResult.sessionId === sessionId) {
      const result = completedStreamResult;
      setMessages(result.messages);
      setProcessDataMap(prev => ({ ...prev, ...result.processDataMap }));
    } else {
      loadMessages(sessionId);
    }
  }, [loadMessages, resetStreamingUI, attachToActiveStream]);

  const handleNewChat = useCallback(() => {
    viewVersionRef.current++;
    currentSessionIdRef.current = null;
    detachStream();
    setProcessDataMap({});
    resetStreamingUI();
    setCurrentSessionId(null);
    setMessages([]);
  }, [resetStreamingUI]);

  useEffect(() => {
    const onDelete = (sessionId: string) => {
      sessionsRequestRef.current++;
      setLoadingSessions(false);
      setSessions(prev => prev.filter(session => session.id !== sessionId));
      void loadSessions();
      if (currentSessionIdRef.current === sessionId) {
        viewVersionRef.current++;
        currentSessionIdRef.current = null;
        detachStream();
        resetStreamingUI();
        setCurrentSessionId(null);
        setMessages([]);
        setProcessDataMap({});
      }
    };
    deleteSubscribers.add(onDelete);
    return () => { deleteSubscribers.delete(onDelete); };
  }, [detachStream, resetStreamingUI, loadSessions]);

  const handleDeleteSession = useCallback(async (sessionId: string) => {
    if (activeStream && (!activeStream.sessionId || activeStream.sessionId === sessionId)) {
      message.warning(t('chat.stopStreamBeforeDelete', 'Stop the active response before deleting this conversation.'));
      return;
    }
    const epoch = getSessionEpoch();
    const deletion = {};
    let finishDeletion!: () => void;
    const done = new Promise<void>(resolve => { finishDeletion = resolve; });
    pendingSessionDeletes.set(deletion, { sessionId, done });
    try {
      const res = await deleteChatSession(sessionId);
      if (epoch !== getSessionEpoch()) return;
      if (res.code !== 200) throw new Error('delete failed');
      deletedSessions.add(sessionId);
      if (latestStream?.sessionId === sessionId) latestStream = null;
      if (completedStreamResult?.sessionId === sessionId) { completedStreamResult = null; completedStreamOwner = null; }
      deleteSubscribers.forEach(listener => listener(sessionId));
      if (!mountedRef.current) return;
      message.success(t('common.deleted'));
    } catch {
      if (mountedRef.current && epoch === getSessionEpoch()) message.error(t('common.deleteFailed'));
    } finally {
      pendingSessionDeletes.delete(deletion);
      finishDeletion();
    }
  }, [loadSessions, resetStreamingUI, t]);

  const handleProviderChange = useCallback((val: string) => {
    setProvider(val);
    setModel('');
  }, []);

  const handleCopyMessage = useCallback((content: string) => {
    const fallbackCopy = (text: string) => {
      const textarea = document.createElement('textarea');
      textarea.value = text;
      textarea.style.position = 'fixed';
      textarea.style.left = '-9999px';
      textarea.style.top = '-9999px';
      document.body.appendChild(textarea);
      textarea.focus();
      textarea.select();
      try {
        const ok = document.execCommand('copy');
        if (ok) {
          message.success(t('common.copied'));
        } else {
          message.error(t('common.copyFailed'));
        }
      } catch {
        message.error(t('common.copyFailed'));
      } finally {
        document.body.removeChild(textarea);
      }
    };
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      navigator.clipboard.writeText(content).then(() => {
        message.success(t('common.copied'));
      }).catch(() => {
        fallbackCopy(content);
      });
    } else {
      fallbackCopy(content);
    }
  }, []);

  const handleApprove = useCallback(async (taskId: string) => {
    const stream = attachedStreamRef.current;
    if (!stream || activeStream !== stream || !stream.pendingApprovals.some(a => a.taskId === taskId && a.status === 'pending')) return;
    const epoch = getSessionEpoch(), version = viewVersionRef.current;
    const valid = () => epoch === getSessionEpoch() && activeStream === stream;
    const visible = () => valid() && mountedRef.current && version === viewVersionRef.current && attachedStreamRef.current === stream;
    setApprovalLoading(true);
    try {
      const res = await approveExecution(taskId);
      if (!valid()) return;
      if (res.code === 200) {
        stream.pendingApprovals = stream.pendingApprovals.map(a => a.taskId === taskId && a.status === 'pending' ? { ...a, status: 'approved' as const } : a);
        notifyStream(stream);
        if (visible()) message.success(t('chat.executionConfirmed'));
      } else if (visible()) {
        message.error(res.message || t('common.error'));
      }
    } catch {
      if (visible()) message.error(t('common.error'));
    } finally {
      if (visible()) setApprovalLoading(false);
    }
  }, []);

  const handleReject = useCallback(async (taskId: string) => {
    const stream = attachedStreamRef.current;
    if (!stream || activeStream !== stream || !stream.pendingApprovals.some(a => a.taskId === taskId && a.status === 'pending')) return;
    const epoch = getSessionEpoch(), version = viewVersionRef.current;
    const valid = () => epoch === getSessionEpoch() && activeStream === stream;
    const visible = () => valid() && mountedRef.current && version === viewVersionRef.current && attachedStreamRef.current === stream;
    setApprovalLoading(true);
    try {
      const res = await rejectExecution(taskId);
      if (!valid()) return;
      if (res.code === 200) {
        stream.pendingApprovals = stream.pendingApprovals.map(a => a.taskId === taskId && a.status === 'pending' ? { ...a, status: 'rejected' as const } : a);
        notifyStream(stream);
        if (visible()) message.success(t('chat.executionCancelled'));
      } else if (visible()) {
        message.error(res.message || t('common.error'));
      }
    } catch {
      if (visible()) message.error(t('common.error'));
    } finally {
      if (visible()) setApprovalLoading(false);
    }
  }, [t]);

  const viewVersion = viewVersionRef.current;
  const doSend = useCallback(async (text: string, retryMessages?: AiChatMessage[]) => {
    if (!mountedRef.current || sessionEpoch !== getSessionEpoch() || viewVersion !== viewVersionRef.current || currentSessionIdRef.current !== currentSessionId) return;
    if (messages !== messagesRef.current) return;
    if (!text || loading || activeStream) return;
    if (currentSessionId && deletedSessions.has(currentSessionId)) return;
    if (pendingSessionDeletes.size) {
      message.warning(t('chat.waitForSessionDelete', 'Wait for the conversation deletion to finish before sending a message.'));
      return;
    }
    viewVersionRef.current++;
    const epoch = sessionEpoch;
    const isCurrentEpoch = () => epoch === getSessionEpoch();

    const userMsg: AiChatMessage = {
      id: Date.now(),
      sessionId: currentSessionId || '',
      role: 'user',
      content: text,
      createdAt: new Date().toISOString(),
    };

    const currentMessages = retryMessages ?? [...messages, userMsg];
    setMessages(currentMessages);
    setInputValue('');
    setLoading(true);
    setIsStreaming(true);
    setStreamingContent('');
    setStreamingPlan(null);
    setStreamingStepIndex(-1);
    setStreamingThinkingLog([]);
    setStreamingAgent('');
    setStreamingStepDescription('');
    setStreamingToolCalls([]);
    setPendingApprovals([]);

    setPlanId(undefined);
    setPlanConfirmationStatus(undefined);

    const actualTargetIds = selectedTargetIds.filter(id => !id.startsWith('cluster_') && id !== 'ungrouped');

    const requestData = {
      sessionId: currentSessionId || undefined,
      message: text,
      provider: provider === 'default' ? undefined : provider,
      model: model || undefined,
      enableTools,
      targetAgentIds: actualTargetIds.length > 0 ? actualTargetIds : undefined,
    };

    let streamSessionId = currentSessionId || '';

    const singletonState: ActiveStreamState = {
      sessionId: streamSessionId,
      controller: null as unknown as AbortController,
      content: '',
      plan: null,
      thinkingLog: [],
      toolCalls: [],
      agent: '',
      stepDescription: '',
      stepIndex: -1,
      isStreaming: true,
      pendingApprovals: [],
      messages: currentMessages,
      onStateChange: undefined,
      planConfirmationStatus: undefined,
      reviewResult: undefined,
      parallelProgress: [],
    };
    completedStreamResult = null;
    completedStreamOwner = null;
    latestStream = singletonState;
    activeStream = singletonState;
    attachedStreamRef.current = singletonState;
    attachToActiveStream();

    const singletonCheckpoints = new Set<number>();
    streamCheckpoints.set(singletonState, singletonCheckpoints);

    const controller = sendChatStream(
      requestData,
      (event: AgentEvent) => {
        if (!isCurrentEpoch() || activeStream !== singletonState) return;
        switch (event.type) {
          case 'SESSION':
            if (event.sessionId) {
              streamSessionId = event.sessionId;
              singletonState.sessionId = event.sessionId;

            }
            break;
          case 'PLAN':
            if (event.plan) {
              singletonState.plan = event.plan;

            }
            break;
          case 'STEP_START':
            if (event.stepIndex !== undefined) {
              singletonState.stepIndex = event.stepIndex;
              singletonState.agent = event.agent || '';
              singletonState.stepDescription = event.stepDescription || '';



            }
            break;
          case 'THINKING':
            if (event.agent !== 'system') {
              singletonState.thinkingLog = [...singletonState.thinkingLog, event.content || ''];

            }
            break;
          case 'TOOL_CALL': {
            const newCall = { toolName: event.toolName || '', toolArgs: event.toolArgs || '' };
            singletonState.toolCalls = [...singletonState.toolCalls, newCall];

            break;
          }
          case 'TOOL_RESULT': {
            const ridx = [...singletonState.toolCalls].reverse().findIndex(tc => tc.toolName === event.toolName && !tc.toolResult);
            if (ridx >= 0) {
              const realIdx = singletonState.toolCalls.length - 1 - ridx;
              singletonState.toolCalls = singletonState.toolCalls.map((tc, i) => i === realIdx ? { ...tc, toolResult: event.toolResult } : tc);
            }
            break;
          }
          case 'CONTENT':
            singletonState.content += (event.content || '');

            break;
          case 'STEP_COMPLETE':
            singletonState.agent = '';
            singletonState.stepDescription = '';


            break;
          case 'ERROR':
            if (attachedStreamRef.current === singletonState) message.error(event.content || t('common.error'));
            break;
          case 'APPROVAL':
            if (event.approvalData && typeof event.approvalData.taskId === 'string') {
              const approval: ApprovalRequest = {
                taskId: event.approvalData.taskId as string,
                description: (event.approvalData.description as string) || event.content || t('chat.needConfirmation'),
                status: 'pending',
              };
              singletonState.pendingApprovals = [...singletonState.pendingApprovals, approval];

            }
            break;
          case 'DONE':
            break;
          case 'ITERATION_START': {
            const iterMsg = `--- ${t('ai.chat.iteration', { current: event.iteration ?? '?', max: event.maxIterations ?? '?' })} ---`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, iterMsg];

            break;
          }
          case 'REFLECTION': {
            const refMsg = `[${t('ai.chat.reflection')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, refMsg];

            break;
          }
          case 'SUBTASK_STARTED': {
            const stAgent = event.metadata?.agentName || event.agent || '';
            const stMsg = `[${t('ai.chat.subtask_started', { agent: stAgent, description: event.content || '' })}]`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, stMsg];

            break;
          }
          case 'SUBTASK_COMPLETED': {
            const scMsg = `[${t('ai.chat.subtask_completed')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, scMsg];

            break;
          }
          case 'SUBTASK_PROGRESS': {
            const spMsg = `[${t('ai.chat.subtask_progress')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, spMsg];

            break;
          }
          case 'PLAN_AWAIT_CONFIRMATION':
            singletonState.planId = event.planId;

            singletonState.planConfirmationStatus = 'awaiting';
            if (event.plan) {
              singletonState.plan = event.plan;

            }

            break;
          case 'PLAN_CONFIRMED':
            singletonState.planId = undefined;

            singletonState.planConfirmationStatus = 'confirmed';

            singletonState.thinkingLog = [...singletonState.thinkingLog, `[${t('ai.chat.plan_confirmed')}]`];

            break;
          case 'PLAN_REJECTED':
            singletonState.planId = undefined;

            singletonState.planConfirmationStatus = 'rejected';

            singletonState.thinkingLog = [...singletonState.thinkingLog, `[${t('ai.chat.plan_rejected')}]`];

            break;
          case 'STEP_RETRY': {
            const retryMsg = `[${t('ai.chat.step_retry')}] Step ${event.stepIndex ?? '?'}: ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, retryMsg];

            break;
          }
          case 'PLAN_SUMMARY':
            if (event.content) {
              singletonState.content += event.content;

            }
            if (event.plan) {
              singletonState.plan = event.plan;

            }
            break;
          case 'REVIEW_START': {
            const rsMsg = `[${t('ai.chat.review_start')}]`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, rsMsg];

            break;
          }
          case 'REVIEW_COMPLETE': {
            singletonState.reviewResult = event.content || '';

            const rcMsg = `[${t('ai.chat.review_complete')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, rcMsg];

            break;
          }
          case 'PARALLEL_START': {
            const pg = event.parallelGroup ?? 0;
            const total = event.totalTasks ?? 0;
            const newProgress = [...(singletonState.parallelProgress || []), { group: pg, completed: 0, total }];
            singletonState.parallelProgress = newProgress;

            const psMsg = `[${t('ai.chat.parallel_start')}] Group ${pg}: ${total} tasks`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, psMsg];

            break;
          }
          case 'PARALLEL_PROGRESS': {
            const ppg = event.parallelGroup ?? 0;
            const ppCompleted = event.completedTasks ?? 0;
            const ppTotal = event.totalTasks ?? 0;
            const updatedProgress = (singletonState.parallelProgress || []).map(p =>
              p.group === ppg ? { ...p, completed: ppCompleted, total: ppTotal } : p
            );
            singletonState.parallelProgress = updatedProgress;

            break;
          }
          case 'PARALLEL_COMPLETE': {
            const pcg = event.parallelGroup ?? 0;
            const updatedPc = (singletonState.parallelProgress || []).map(p =>
              p.group === pcg ? { ...p, completed: p.total } : p
            );
            singletonState.parallelProgress = updatedPc;

            const pcMsg = `[${t('ai.chat.parallel_complete')}] Group ${pcg}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, pcMsg];

            break;
          }
          case 'MEMORY_RETRIEVED': {
            const memMsg = `[${t('ai.chat.memory_retrieved')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, memMsg];

            break;
          }
          case 'SOP_MATCHED': {
            const sopMsg = `[${t('ai.chat.sop_matched')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, sopMsg];

            break;
          }
          case 'SOP_APPLIED': {
            const sopAppliedMsg = `[${t('ai.chat.sop_applied')}] SOP #${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, sopAppliedMsg];

            break;
          }
          case 'TASK_CLASSIFIED': {
            const taskClassifiedMsg = `[${t('ai.chat.task_classified')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, taskClassifiedMsg];

            break;
          }
          case 'STEP_CHECKPOINT': {
            const checkpointMsg = `[${t('ai.chat.step_checkpoint')}] Step ${event.stepIndex}: ${event.stepDescription || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, checkpointMsg];

            if (event.stepIndex !== undefined) {
              singletonCheckpoints.add(event.stepIndex);
            }
            break;
          }
          case 'STEP_CONDITION_EVAL': {
            const condResult = event.metadata?.result === 'true' ? '✓' : '✗';
            const condMsg = `[${t('ai.chat.step_condition_eval')}] Step ${event.stepIndex}: ${event.content || ''} → ${condResult}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, condMsg];

            break;
          }
          case 'VARIABLE_SET': {
            const varMsg = `[${t('ai.chat.variable_set')}] ${event.metadata?.varName || ''} = ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, varMsg];

            break;
          }
        }
        notifyStream(singletonState);
      },
      (err: Error) => {
        if (!isCurrentEpoch() || activeStream !== singletonState) return;
        singletonState.isStreaming = false;
        const result: CompletedStreamResult = {
          sessionId: streamSessionId,
          messages: singletonState.content ? [...singletonState.messages, {
            id: Date.now() + 1, sessionId: streamSessionId, role: 'assistant',
            content: singletonState.content, createdAt: new Date().toISOString(),
          }] : [...singletonState.messages],
          processDataMap: {},
        };
        streamResults.set(singletonState, result);
        completedStreamResult = result;
        completedStreamOwner = singletonState;
        if (streamSubscribers.get(singletonState)?.size) {
          notifyStream(singletonState);
          message.error(t('chat.streamError') + err.message);
        }
        activeStream = null;
      },
      () => {
        if (!isCurrentEpoch() || activeStream !== singletonState) return;
        singletonState.isStreaming = false;

        const tempMsgId = Date.now() + 1;
        if (singletonState.content) {
          const assistantMsg: AiChatMessage = {
            id: tempMsgId,
            sessionId: streamSessionId,
            role: 'assistant',
            content: singletonState.content,
            createdAt: new Date().toISOString(),
          };
          singletonState.messages = [...singletonState.messages, assistantMsg];
        }

        const hasProcessData = singletonState.plan || singletonState.thinkingLog.length > 0 || singletonState.toolCalls.length > 0;
        const localProcessData: Record<number, ProcessData> = {};
        if (hasProcessData) {
          localProcessData[tempMsgId] = {
            plan: singletonState.plan,
            thinkingLog: [...singletonState.thinkingLog],
            toolCalls: [...singletonState.toolCalls],
            reviewResult: singletonState.reviewResult,
          };
        }

        const result = { sessionId: streamSessionId, messages: [...singletonState.messages], processDataMap: localProcessData };
        streamResults.set(singletonState, result);
        completedStreamOwner = singletonState;
        completedStreamResult = result;
        const hasSubscriber = !!streamSubscribers.get(singletonState)?.size;
        notifyStream(singletonState);
        const isViewingThisSession = hasSubscriber || (mountedRef.current && attachedStreamRef.current === singletonState);

        if (isViewingThisSession && mountedRef.current && currentSessionIdRef.current === streamSessionId) {
          setMessages([...singletonState.messages]);
          if (Object.keys(localProcessData).length > 0) {
            setProcessDataMap(prev => ({ ...prev, ...localProcessData }));
          }
          resetStreamingUI();
        } else if (!isViewingThisSession) {
          completedStreamOwner = singletonState;
          completedStreamResult = {
            sessionId: streamSessionId,
            messages: [...singletonState.messages],
            processDataMap: localProcessData,
          };
        }

        activeStream = null;
        if (isViewingThisSession) attachedStreamRef.current = null;
        if (mountedRef.current) loadSessions();

        const finalizeVersion = viewVersionRef.current;
        const finalize = async () => {
          const valid = () => isCurrentEpoch() && latestStream === singletonState && !!streamSessionId && !deletedSessions.has(streamSessionId);
          const waitForDeletes = async () => {
            while (valid()) {
              const pending = [...pendingSessionDeletes.values()].filter(deletion => deletion.sessionId === streamSessionId);
              if (!pending.length) return;
              await Promise.all(pending.map(deletion => deletion.done));
            }
          };
          await waitForDeletes();
          if (valid()) {
            try {
              const res = await getChatMessages(streamSessionId);
              await waitForDeletes();
              if (valid() && res.code === 200) {
                const msgs = res.data || [];
                const newProcessDataMap: Record<number, ProcessData> = {};
                for (const msg of msgs) {
                  if (msg.role === 'assistant' && msg.processData) {
                    try {
                      const parsed = JSON.parse(msg.processData) as ProcessData;
                      newProcessDataMap[msg.id] = parsed;
                    } catch { }
                  }
                }
                if (mountedRef.current && viewVersionRef.current === finalizeVersion && currentSessionIdRef.current === streamSessionId && !activeStream) {
                  // Only update messages if server returned data, otherwise preserve local state
                  // This prevents conversation from disappearing when server returns empty array
                  if (msgs.length > 0) {
                    setMessages(msgs);
                  }
                  if (Object.keys(newProcessDataMap).length > 0) {
                    setProcessDataMap(prev => ({ ...prev, ...newProcessDataMap }));
                  }
                }
                if (!activeStream && completedStreamOwner === singletonState && completedStreamResult && completedStreamResult.sessionId === streamSessionId) {
                  completedStreamResult = {
                    sessionId: streamSessionId,
                    messages: msgs.length ? msgs : completedStreamResult.messages,
                    processDataMap: { ...completedStreamResult.processDataMap, ...newProcessDataMap },
                  };
                }
              }
            } catch { /* Ignore failed finalization. */ }
          }
        };

        if (isCurrentEpoch() && hasProcessData && streamSessionId) {
          const processDataJson = JSON.stringify({
            plan: singletonState.plan,
            thinkingLog: [...singletonState.thinkingLog],
            toolCalls: [...singletonState.toolCalls],
            reviewResult: singletonState.reviewResult,
          });
          saveProcessData(streamSessionId, processDataJson).then(() => finalize()).catch(() => finalize());
        } else {
          finalize();
        }

        setTimeout(() => { if (isCurrentEpoch()) inputRef.current?.focus(); }, 100);
      },
    );

    singletonState.controller = controller;
    abortRef.current = controller;
  }, [sessionEpoch, viewVersion, loading, currentSessionId, provider, model, enableTools, selectedTargetIds, loadSessions, resetStreamingUI, attachToActiveStream, messages, t]);

  const handleSend = useCallback(() => {
    doSend(inputValue.trim());
  }, [inputValue, doSend]);

  const handleRetry = useCallback(() => {
    if (messages !== messagesRef.current) return;
    const lastUserIndex = messages.length - 1 - [...messages].reverse().findIndex(m => m.role === 'user');
    if (lastUserIndex < messages.length) {
      doSend(messages[lastUserIndex].content, messages.slice(0, lastUserIndex + 1));
    }
  }, [messages, doSend]);

  const handleStop = useCallback(() => {
    if (!activeStream || attachedStreamRef.current !== activeStream) return;
    const stoppedStream = activeStream;
    viewVersionRef.current++;
    stoppedStream.isStreaming = false;
    const msgId = Date.now() + 1;
    const stoppedMessages = [...stoppedStream.messages];
    if (stoppedStream.content) {
      stoppedMessages.push({
        id: msgId, sessionId: stoppedStream.sessionId, role: 'assistant',
        content: stoppedStream.content + '\n\n*[' + t('chat.stopped') + ']*',
        createdAt: new Date().toISOString(),
      });
    }
    const processDataMap: Record<number, ProcessData> = {};
    if (stoppedStream.plan || stoppedStream.thinkingLog.length || stoppedStream.toolCalls.length) {
      processDataMap[msgId] = { plan: stoppedStream.plan, thinkingLog: [...stoppedStream.thinkingLog], toolCalls: [...stoppedStream.toolCalls] };
    }
    const result = { sessionId: stoppedStream.sessionId, messages: stoppedMessages, processDataMap };
    streamResults.set(stoppedStream, result);
    completedStreamResult = result;
    completedStreamOwner = stoppedStream;
    notifyStream(stoppedStream);
    activeStream = null;
    stoppedStream.controller?.abort();
  }, [currentSessionId, resetStreamingUI, t]);

  const handleSuggestedPrompt = useCallback((text: string) => {
    doSend(text);
  }, [doSend]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }, [handleSend]);

  const handleConfirmPlan = useCallback(async () => {
    const stream = attachedStreamRef.current;
    if (!currentSessionId || !planId || !stream || activeStream !== stream || stream.planId !== planId || stream.planConfirmationStatus !== 'awaiting') return;
    const epoch = getSessionEpoch(), version = viewVersionRef.current;
    const visible = () => epoch === getSessionEpoch() && activeStream === stream && mountedRef.current && version === viewVersionRef.current && attachedStreamRef.current === stream;
    setPlanConfirmLoading(true);
    try {
      const res = await confirmPlan(currentSessionId, planId);
      if (epoch === getSessionEpoch() && activeStream === stream && res.code === 200 && stream.planId === planId && stream.planConfirmationStatus === 'awaiting') {
        stream.planId = undefined;
        stream.planConfirmationStatus = 'confirmed';
        notifyStream(stream);
      }
      if (visible() && res.code !== 200) message.error(t('common.error'));
    } catch {
      if (visible()) message.error(t('common.error'));
    } finally {
      if (visible()) setPlanConfirmLoading(false);
    }
  }, [currentSessionId, planId, t]);

  const handleRejectPlan = useCallback(async () => {
    const stream = attachedStreamRef.current;
    if (!currentSessionId || !planId || !stream || activeStream !== stream || stream.planId !== planId || stream.planConfirmationStatus !== 'awaiting') return;
    const epoch = getSessionEpoch(), version = viewVersionRef.current;
    const visible = () => epoch === getSessionEpoch() && activeStream === stream && mountedRef.current && version === viewVersionRef.current && attachedStreamRef.current === stream;
    setPlanConfirmLoading(true);
    try {
      const res = await rejectPlan(currentSessionId, planId);
      if (epoch === getSessionEpoch() && activeStream === stream && res.code === 200 && stream.planId === planId && stream.planConfirmationStatus === 'awaiting') {
        stream.planId = undefined;
        stream.planConfirmationStatus = 'rejected';
        notifyStream(stream);
      }
      if (visible() && res.code !== 200) message.error(t('common.error'));
    } catch {
      if (visible()) message.error(t('common.error'));
    } finally {
      if (visible()) setPlanConfirmLoading(false);
    }
  }, [currentSessionId, planId, t]);

  const handleApproveCheckpoint = useCallback(async (stepIndex: number) => {
    const stream = attachedStreamRef.current;
    const checkpoints = stream && streamCheckpoints.get(stream);
    if (!currentSessionId || !stream || activeStream !== stream || !checkpoints?.has(stepIndex)) return;
    const epoch = getSessionEpoch(), version = viewVersionRef.current;
    const valid = () => epoch === getSessionEpoch() && activeStream === stream;
    const visible = () => valid() && mountedRef.current && version === viewVersionRef.current && attachedStreamRef.current === stream;
    setCheckpointLoading(stepIndex);
    try {
      const res = await approveCheckpoint(currentSessionId, stepIndex);
      if (!valid()) return;
      if (res.code === 200) {
        checkpoints.delete(stepIndex);
        notifyStream(stream);
      } else if (visible()) {
        message.error(t('dag.checkpoint.approveFailed', 'Approve failed'));
      }
    } catch {
      if (visible()) message.error(t('dag.checkpoint.approveFailed', 'Approve failed'));
    } finally {
      if (visible()) setCheckpointLoading(null);
    }
  }, [currentSessionId, t]);

  const handleRejectCheckpoint = useCallback(async (stepIndex: number) => {
    const stream = attachedStreamRef.current;
    const checkpoints = stream && streamCheckpoints.get(stream);
    if (!currentSessionId || !stream || activeStream !== stream || !checkpoints?.has(stepIndex)) return;
    const epoch = getSessionEpoch(), version = viewVersionRef.current;
    const valid = () => epoch === getSessionEpoch() && activeStream === stream;
    const visible = () => valid() && mountedRef.current && version === viewVersionRef.current && attachedStreamRef.current === stream;
    setCheckpointLoading(stepIndex);
    try {
      const res = await rejectCheckpoint(currentSessionId, stepIndex);
      if (!valid()) return;
      if (res.code === 200) {
        checkpoints.delete(stepIndex);
        notifyStream(stream);
      } else if (visible()) {
        message.error(t('dag.checkpoint.rejectFailed', 'Reject failed'));
      }
    } catch {
      if (visible()) message.error(t('dag.checkpoint.rejectFailed', 'Reject failed'));
    } finally {
      if (visible()) setCheckpointLoading(null);
    }
  }, [currentSessionId, t]);

  return {
    token,
    sessions, currentSessionId, loadingSessions,
    messages, loading,
    inputValue, setInputValue,
    provider, model, setModel, enableTools, setEnableTools,
    selectedTargetIds, setSelectedTargetIds, targetTreeData, loadingTargets,
    streamingContent, streamingPlan, streamingStepIndex,
    streamingThinkingLog, streamingAgent, streamingStepDescription,
    streamingToolCalls, isStreaming, pendingApprovals, approvalLoading,
    planConfirmationStatus, reviewResult, parallelProgress, planConfirmLoading,
    processDataMap, processModalVisible, setProcessModalVisible,
    viewingProcessMsgId, setViewingProcessMsgId,
    messagesEndRef, inputRef,
    userScrolledUp, handleMessagesScroll, forceScrollToBottom,
    handleSelectSession, handleNewChat, handleDeleteSession,
    handleProviderChange, handleCopyMessage, handleSend, handleRetry,
    handleStop, handleSuggestedPrompt, handleKeyDown,
    handleApprove, handleReject,
    handleConfirmPlan, handleRejectPlan,
    handleApproveCheckpoint, handleRejectCheckpoint,
    checkpointSteps, checkpointLoading,
    hoveredMsgId, setHoveredMsgId,
  };
}
