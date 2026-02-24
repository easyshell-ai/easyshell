import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { message, theme } from 'antd';
import { useTranslation } from 'react-i18next';
import { getChatSessions, getChatMessages, deleteChatSession, sendChatStream, approveExecution, rejectExecution, saveProcessData, confirmPlan, rejectPlan, approveCheckpoint, rejectCheckpoint } from '../api/ai';
import { getClusterList, getClusterDetail } from '../api/cluster';
import { getHostList } from '../api/host';
import type { AiChatSession, AiChatMessage, ClusterDetailVO, Agent, AgentEvent, ExecutionPlan } from '../types';
import type { ApprovalRequest, ProcessData, ActiveStreamState, CompletedStreamResult, TreeNode } from '../pages/ai/chat/types';

// Module-level mutable state — survives React navigation (unmount/remount).
// These are intentionally NOT React state.
let activeStream: ActiveStreamState | null = null;
let completedStreamResult: CompletedStreamResult | null = null;

export function useAiChat() {
  const { token } = theme.useToken();
  const { t } = useTranslation();

  const [sessions, setSessions] = useState<AiChatSession[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<AiChatMessage[]>([]);
  const [inputValue, setInputValue] = useState('');
  const [loading, setLoading] = useState(false);
  const [provider, setProvider] = useState('default');
  const [model, setModel] = useState('');
  const [enableTools, setEnableTools] = useState(true);
  const [loadingSessions, setLoadingSessions] = useState(false);
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

  // Refs to capture current streaming state inside callbacks (closures capture stale state)
  const streamingPlanRef = useRef<ExecutionPlan | null>(null);
  const streamingThinkingLogRef = useRef<string[]>([]);
  const streamingToolCallsRef = useRef<Array<{ toolName: string; toolArgs: string; toolResult?: string }>>([]);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<any>(null);
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

  useEffect(() => {
    if (activeStream && activeStream.isStreaming) {
      setCurrentSessionId(activeStream.sessionId);
      attachToActiveStream();
    } else if (completedStreamResult) {
      const result = completedStreamResult;
      completedStreamResult = null;
      setCurrentSessionId(result.sessionId);
      setMessages(result.messages);
      setProcessDataMap(prev => ({ ...prev, ...result.processDataMap }));
    }

    return () => {
      if (activeStream) {
        activeStream.onStateChange = undefined;
      }
    };
  }, []);

  const loadSessions = useCallback(async () => {
    if (!initialSessionsLoadedRef.current) {
      setLoadingSessions(true);
    }
    try {
      const res = await getChatSessions();
      if (res.code === 200) {
        setSessions(res.data || []);
      }
    } catch {
      // ignore
    } finally {
      setLoadingSessions(false);
      initialSessionsLoadedRef.current = true;
    }
  }, []);

  useEffect(() => {
    loadSessions();
  }, [loadSessions]);

  useEffect(() => {
    const loadTargets = async () => {
      setLoadingTargets(true);
      try {
        const [clusterRes, hostRes] = await Promise.all([
          getClusterList(),
          getHostList(),
        ]);

        const allHosts = hostRes.code === 200 ? (hostRes.data || []) : [];
        setHosts(allHosts);

        if (clusterRes.code === 200 && clusterRes.data) {
          const detailPromises = clusterRes.data.map(c => getClusterDetail(c.id));
          const details = await Promise.all(detailPromises);
          const clusterDetails = details
            .filter(d => d.code === 200 && d.data)
            .map(d => d.data as ClusterDetailVO);
          setClusters(clusterDetails);
        }
      } catch {
        // ignore
      } finally {
        setLoadingTargets(false);
      }
    };
    loadTargets();
  }, []);

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
    try {
      const res = await getChatMessages(sessionId);
      if (res.code === 200) {
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
      message.error(t('chat.loadMessagesFailed'));
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
    userScrolledUpRef.current = false;
    setUserScrolledUp(false);
    setPlanConfirmationStatus(undefined);
    setReviewResult(undefined);
    setParallelProgress([]);
    setPlanConfirmLoading(false);
    setCheckpointSteps(new Set());
    setCheckpointLoading(null);
  }, []);

  const attachToActiveStream = useCallback(() => {
    if (!activeStream) return;
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
    setPlanConfirmationStatus(activeStream.planConfirmationStatus);
    setReviewResult(activeStream.reviewResult);
    setParallelProgress(activeStream.parallelProgress || []);
    if (activeStream.messages.length > 0) {
      setMessages([...activeStream.messages]);
    }
    abortRef.current = activeStream.controller;
    activeStream.onStateChange = () => {
      if (!activeStream) return;
      setStreamingContent(activeStream.content);
      setStreamingPlan(activeStream.plan);
      setStreamingStepIndex(activeStream.stepIndex);
      setStreamingThinkingLog([...activeStream.thinkingLog]);
      setStreamingAgent(activeStream.agent);
      setStreamingStepDescription(activeStream.stepDescription);
      setStreamingToolCalls([...activeStream.toolCalls]);
      setPendingApprovals([...activeStream.pendingApprovals]);
      setPlanConfirmationStatus(activeStream.planConfirmationStatus);
      setReviewResult(activeStream.reviewResult);
      setParallelProgress(activeStream.parallelProgress || []);
    };
  }, []);

  const handleSelectSession = useCallback((sessionId: string) => {
    if (activeStream && activeStream.sessionId !== sessionId) {
      activeStream.onStateChange = undefined;
      resetStreamingUI();
    }

    setCurrentSessionId(sessionId);

    if (activeStream && activeStream.sessionId === sessionId) {
      attachToActiveStream();
    } else if (completedStreamResult && completedStreamResult.sessionId === sessionId) {
      const result = completedStreamResult;
      completedStreamResult = null;
      setMessages(result.messages);
      setProcessDataMap(prev => ({ ...prev, ...result.processDataMap }));
    } else {
      loadMessages(sessionId);
    }
  }, [loadMessages, resetStreamingUI, attachToActiveStream]);

  const handleNewChat = useCallback(() => {
    if (activeStream) {
      activeStream.onStateChange = undefined;
    }
    resetStreamingUI();
    setCurrentSessionId(null);
    setMessages([]);
  }, [resetStreamingUI]);

  const handleDeleteSession = useCallback(async (sessionId: string) => {
    try {
      await deleteChatSession(sessionId);
      message.success(t('common.deleted'));
      if (currentSessionId === sessionId) {
        setCurrentSessionId(null);
        setMessages([]);
      }
      loadSessions();
    } catch {
      message.error(t('common.deleteFailed'));
    }
  }, [currentSessionId, loadSessions, t]);

  const handleProviderChange = useCallback((val: string) => {
    setProvider(val);
    setModel('');
  }, []);

  const handleCopyMessage = useCallback((content: string) => {
    navigator.clipboard.writeText(content).then(() => {
      message.success(t('common.copied'));
    }).catch(() => {
      message.error(t('common.copyFailed'));
    });
  }, []);

  const handleApprove = useCallback(async (taskId: string) => {
    setApprovalLoading(true);
    try {
      const res = await approveExecution(taskId);
      if (res.code === 200) {
        message.success(t('chat.executionConfirmed'));
        setPendingApprovals(prev => prev.map(a => a.taskId === taskId ? { ...a, status: 'approved' as const } : a));
      } else {
        message.error(res.message || t('common.error'));
      }
    } catch {
      message.error(t('common.error'));
    } finally {
      setApprovalLoading(false);
    }
  }, []);

  const handleReject = useCallback(async (taskId: string) => {
    setApprovalLoading(true);
    try {
      const res = await rejectExecution(taskId);
      if (res.code === 200) {
        message.success(t('chat.executionCancelled'));
        setPendingApprovals(prev => prev.map(a => a.taskId === taskId ? { ...a, status: 'rejected' as const } : a));
      } else {
        message.error(res.message || t('common.error'));
      }
    } catch {
      message.error(t('common.error'));
    } finally {
      setApprovalLoading(false);
    }
  }, [t]);

  const doSend = useCallback(async (text: string) => {
    if (!text || loading) return;

    const userMsg: AiChatMessage = {
      id: Date.now(),
      sessionId: currentSessionId || '',
      role: 'user',
      content: text,
      createdAt: new Date().toISOString(),
    };

    const currentMessages = [...messages, userMsg];
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
    activeStream = singletonState;

    const controller = sendChatStream(
      requestData,
      (event: AgentEvent) => {
        switch (event.type) {
          case 'SESSION':
            if (event.sessionId) {
              streamSessionId = event.sessionId;
              singletonState.sessionId = event.sessionId;
              setCurrentSessionId(event.sessionId);
            }
            break;
          case 'PLAN':
            if (event.plan) {
              singletonState.plan = event.plan;
              setStreamingPlan(event.plan);
            }
            break;
          case 'STEP_START':
            if (event.stepIndex !== undefined) {
              singletonState.stepIndex = event.stepIndex;
              singletonState.agent = event.agent || '';
              singletonState.stepDescription = event.stepDescription || '';
              setStreamingStepIndex(event.stepIndex);
              setStreamingAgent(event.agent || '');
              setStreamingStepDescription(event.stepDescription || '');
            }
            break;
          case 'THINKING':
            if (event.agent !== 'system') {
              singletonState.thinkingLog = [...singletonState.thinkingLog, event.content || ''];
              setStreamingThinkingLog(prev => [...prev, event.content || '']);
            }
            break;
          case 'TOOL_CALL': {
            const newCall = { toolName: event.toolName || '', toolArgs: event.toolArgs || '' };
            singletonState.toolCalls = [...singletonState.toolCalls, newCall];
            setStreamingToolCalls(prev => [...prev, newCall]);
            break;
          }
          case 'TOOL_RESULT': {
            const ridx = [...singletonState.toolCalls].reverse().findIndex(tc => tc.toolName === event.toolName && !tc.toolResult);
            if (ridx >= 0) {
              const realIdx = singletonState.toolCalls.length - 1 - ridx;
              singletonState.toolCalls = singletonState.toolCalls.map((tc, i) => i === realIdx ? { ...tc, toolResult: event.toolResult } : tc);
            }
            setStreamingToolCalls(prev => {
              const idx = [...prev].reverse().findIndex(tc => tc.toolName === event.toolName && !tc.toolResult);
              if (idx >= 0) {
                const ri = prev.length - 1 - idx;
                return prev.map((tc, i) => i === ri ? { ...tc, toolResult: event.toolResult } : tc);
              }
              return prev;
            });
            break;
          }
          case 'CONTENT':
            singletonState.content += (event.content || '');
            setStreamingContent(singletonState.content);
            break;
          case 'STEP_COMPLETE':
            singletonState.agent = '';
            singletonState.stepDescription = '';
            setStreamingAgent('');
            setStreamingStepDescription('');
            break;
          case 'ERROR':
            message.error(event.content || t('common.error'));
            break;
          case 'APPROVAL':
            if (event.approvalData && typeof event.approvalData.taskId === 'string') {
              const approval: ApprovalRequest = {
                taskId: event.approvalData.taskId as string,
                description: (event.approvalData.description as string) || event.content || t('chat.needConfirmation'),
                status: 'pending',
              };
              singletonState.pendingApprovals = [...singletonState.pendingApprovals, approval];
              setPendingApprovals(prev => [...prev, approval]);
            }
            break;
          case 'DONE':
            break;
          case 'ITERATION_START': {
            const iterMsg = `--- ${t('ai.chat.iteration', { current: event.iteration ?? '?', max: event.maxIterations ?? '?' })} ---`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, iterMsg];
            setStreamingThinkingLog(prev => [...prev, iterMsg]);
            break;
          }
          case 'REFLECTION': {
            const refMsg = `[${t('ai.chat.reflection')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, refMsg];
            setStreamingThinkingLog(prev => [...prev, refMsg]);
            break;
          }
          case 'SUBTASK_STARTED': {
            const stAgent = event.metadata?.agentName || event.agent || '';
            const stMsg = `[${t('ai.chat.subtask_started', { agent: stAgent, description: event.content || '' })}]`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, stMsg];
            setStreamingThinkingLog(prev => [...prev, stMsg]);
            break;
          }
          case 'SUBTASK_COMPLETED': {
            const scMsg = `[${t('ai.chat.subtask_completed')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, scMsg];
            setStreamingThinkingLog(prev => [...prev, scMsg]);
            break;
          }
          case 'SUBTASK_PROGRESS': {
            const spMsg = `[${t('ai.chat.subtask_progress')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, spMsg];
            setStreamingThinkingLog(prev => [...prev, spMsg]);
            break;
          }
          case 'PLAN_AWAIT_CONFIRMATION':
            singletonState.planConfirmationStatus = 'awaiting';
            if (event.plan) {
              singletonState.plan = event.plan;
              setStreamingPlan(event.plan);
            }
            setPlanConfirmationStatus('awaiting');
            break;
          case 'PLAN_CONFIRMED':
            singletonState.planConfirmationStatus = 'confirmed';
            setPlanConfirmationStatus('confirmed');
            singletonState.thinkingLog = [...singletonState.thinkingLog, `[${t('ai.chat.plan_confirmed')}]`];
            setStreamingThinkingLog(prev => [...prev, `[${t('ai.chat.plan_confirmed')}]`]);
            break;
          case 'PLAN_REJECTED':
            singletonState.planConfirmationStatus = 'rejected';
            setPlanConfirmationStatus('rejected');
            singletonState.thinkingLog = [...singletonState.thinkingLog, `[${t('ai.chat.plan_rejected')}]`];
            setStreamingThinkingLog(prev => [...prev, `[${t('ai.chat.plan_rejected')}]`]);
            break;
          case 'STEP_RETRY': {
            const retryMsg = `[${t('ai.chat.step_retry')}] Step ${event.stepIndex ?? '?'}: ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, retryMsg];
            setStreamingThinkingLog(prev => [...prev, retryMsg]);
            break;
          }
          case 'PLAN_SUMMARY':
            if (event.content) {
              singletonState.content += event.content;
              setStreamingContent(singletonState.content);
            }
            if (event.plan) {
              singletonState.plan = event.plan;
              setStreamingPlan(event.plan);
            }
            break;
          case 'REVIEW_START': {
            const rsMsg = `[${t('ai.chat.review_start')}]`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, rsMsg];
            setStreamingThinkingLog(prev => [...prev, rsMsg]);
            break;
          }
          case 'REVIEW_COMPLETE': {
            singletonState.reviewResult = event.content || '';
            setReviewResult(event.content || '');
            const rcMsg = `[${t('ai.chat.review_complete')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, rcMsg];
            setStreamingThinkingLog(prev => [...prev, rcMsg]);
            break;
          }
          case 'PARALLEL_START': {
            const pg = event.parallelGroup ?? 0;
            const total = event.totalTasks ?? 0;
            const newProgress = [...(singletonState.parallelProgress || []), { group: pg, completed: 0, total }];
            singletonState.parallelProgress = newProgress;
            setParallelProgress([...newProgress]);
            const psMsg = `[${t('ai.chat.parallel_start')}] Group ${pg}: ${total} tasks`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, psMsg];
            setStreamingThinkingLog(prev => [...prev, psMsg]);
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
            setParallelProgress([...updatedProgress]);
            break;
          }
          case 'PARALLEL_COMPLETE': {
            const pcg = event.parallelGroup ?? 0;
            const updatedPc = (singletonState.parallelProgress || []).map(p =>
              p.group === pcg ? { ...p, completed: p.total } : p
            );
            singletonState.parallelProgress = updatedPc;
            setParallelProgress([...updatedPc]);
            const pcMsg = `[${t('ai.chat.parallel_complete')}] Group ${pcg}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, pcMsg];
            setStreamingThinkingLog(prev => [...prev, pcMsg]);
            break;
          }
          case 'MEMORY_RETRIEVED': {
            const memMsg = `[${t('ai.chat.memory_retrieved')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, memMsg];
            setStreamingThinkingLog(prev => [...prev, memMsg]);
            break;
          }
          case 'SOP_MATCHED': {
            const sopMsg = `[${t('ai.chat.sop_matched')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, sopMsg];
            setStreamingThinkingLog(prev => [...prev, sopMsg]);
            break;
          }
          case 'SOP_APPLIED': {
            const sopAppliedMsg = `[${t('ai.chat.sop_applied')}] SOP #${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, sopAppliedMsg];
            setStreamingThinkingLog(prev => [...prev, sopAppliedMsg]);
            break;
          }
          case 'TASK_CLASSIFIED': {
            const taskClassifiedMsg = `[${t('ai.chat.task_classified')}] ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, taskClassifiedMsg];
            setStreamingThinkingLog(prev => [...prev, taskClassifiedMsg]);
            break;
          }
          case 'STEP_CHECKPOINT': {
            const checkpointMsg = `[${t('ai.chat.step_checkpoint')}] Step ${event.stepIndex}: ${event.stepDescription || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, checkpointMsg];
            setStreamingThinkingLog(prev => [...prev, checkpointMsg]);
            if (event.stepIndex !== undefined) {
              setCheckpointSteps(prev => new Set(prev).add(event.stepIndex!));
            }
            break;
          }
          case 'STEP_CONDITION_EVAL': {
            const condResult = event.metadata?.result === 'true' ? '✓' : '✗';
            const condMsg = `[${t('ai.chat.step_condition_eval')}] Step ${event.stepIndex}: ${event.content || ''} → ${condResult}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, condMsg];
            setStreamingThinkingLog(prev => [...prev, condMsg]);
            break;
          }
          case 'VARIABLE_SET': {
            const varMsg = `[${t('ai.chat.variable_set')}] ${event.metadata?.varName || ''} = ${event.content || ''}`;
            singletonState.thinkingLog = [...singletonState.thinkingLog, varMsg];
            setStreamingThinkingLog(prev => [...prev, varMsg]);
            break;
          }
        }
        if (singletonState.onStateChange) {
          singletonState.onStateChange();
        }
      },
      (err: Error) => {
        message.error(t('chat.streamError') + err.message);
        singletonState.isStreaming = false;
        activeStream = null;
        setLoading(false);
        setIsStreaming(false);
      },
      () => {
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

        const isViewingThisSession = currentSessionIdRef.current === streamSessionId;

        if (isViewingThisSession) {
          setMessages([...singletonState.messages]);
          if (Object.keys(localProcessData).length > 0) {
            setProcessDataMap(prev => ({ ...prev, ...localProcessData }));
          }
          resetStreamingUI();
        } else {
          completedStreamResult = {
            sessionId: streamSessionId,
            messages: [...singletonState.messages],
            processDataMap: localProcessData,
          };
        }

        activeStream = null;
        loadSessions();

        const finalize = () => {
          if (streamSessionId) {
            getChatMessages(streamSessionId).then(res => {
              if (res.code === 200) {
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
                if (currentSessionIdRef.current === streamSessionId) {
                  setMessages(msgs);
                  if (Object.keys(newProcessDataMap).length > 0) {
                    setProcessDataMap(prev => ({ ...prev, ...newProcessDataMap }));
                  }
                } else if (completedStreamResult && completedStreamResult.sessionId === streamSessionId) {
                  completedStreamResult = {
                    sessionId: streamSessionId,
                    messages: msgs,
                    processDataMap: newProcessDataMap,
                  };
                }
              }
            }).catch(() => {});
          }
        };

        if (hasProcessData && streamSessionId) {
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

        setTimeout(() => { inputRef.current?.focus(); }, 100);
      },
    );

    singletonState.controller = controller;
    abortRef.current = controller;
  }, [loading, currentSessionId, provider, model, enableTools, selectedTargetIds, loadSessions, resetStreamingUI, messages, t]);

  const handleSend = useCallback(() => {
    doSend(inputValue.trim());
  }, [inputValue, doSend]);

  const handleRetry = useCallback(() => {
    const lastUserMsg = [...messages].reverse().find(m => m.role === 'user');
    if (lastUserMsg) {
      setMessages(prev => {
        const idx = prev.length - 1;
        if (idx >= 0 && prev[idx].role === 'assistant') {
          return prev.slice(0, idx);
        }
        return prev;
      });
      doSend(lastUserMsg.content);
    }
  }, [messages, doSend]);

  const handleStop = useCallback(() => {
    abortRef.current?.abort();
    const stoppedContent = activeStream ? activeStream.content : streamingContent;
    const plan = activeStream ? activeStream.plan : streamingPlanRef.current;
    const thinkingLog = activeStream ? activeStream.thinkingLog : streamingThinkingLogRef.current;
    const toolCalls = activeStream ? activeStream.toolCalls : streamingToolCallsRef.current;

    const msgId = Date.now() + 1;
    if (stoppedContent) {
      const assistantMsg: AiChatMessage = {
        id: msgId,
        sessionId: currentSessionId || '',
        role: 'assistant',
        content: stoppedContent + '\n\n*[' + t('chat.stopped') + ']*',
        createdAt: new Date().toISOString(),
      };
      setMessages(prev => [...prev, assistantMsg]);
    }
    if (plan || thinkingLog.length > 0 || toolCalls.length > 0) {
      setProcessDataMap(prev => ({
        ...prev,
        [msgId]: { plan, thinkingLog: [...thinkingLog], toolCalls: [...toolCalls] },
      }));
    }
    activeStream = null;
    setStreamingContent('');
    setStreamingPlan(null);
    setStreamingStepIndex(-1);
    setStreamingThinkingLog([]);
    setStreamingAgent('');
    setStreamingStepDescription('');
    setStreamingToolCalls([]);
    setLoading(false);
    setIsStreaming(false);
  }, [streamingContent, currentSessionId]);

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
    if (!currentSessionId) return;
    setPlanConfirmLoading(true);
    try {
      await confirmPlan(currentSessionId);
    } catch {
      message.error(t('common.error'));
    } finally {
      setPlanConfirmLoading(false);
    }
  }, [currentSessionId, t]);

  const handleRejectPlan = useCallback(async () => {
    if (!currentSessionId) return;
    setPlanConfirmLoading(true);
    try {
      await rejectPlan(currentSessionId);
    } catch {
      message.error(t('common.error'));
    } finally {
      setPlanConfirmLoading(false);
    }
  }, [currentSessionId, t]);

  const handleApproveCheckpoint = useCallback(async (stepIndex: number) => {
    if (!currentSessionId) return;
    setCheckpointLoading(stepIndex);
    try {
      const res = await approveCheckpoint(currentSessionId, stepIndex);
      if (res.code === 200) {
        setCheckpointSteps(prev => {
          const next = new Set(prev);
          next.delete(stepIndex);
          return next;
        });
      } else {
        message.error(t('dag.checkpoint.approveFailed', 'Approve failed'));
      }
    } catch {
      message.error(t('dag.checkpoint.approveFailed', 'Approve failed'));
    } finally {
      setCheckpointLoading(null);
    }
  }, [currentSessionId, t]);

  const handleRejectCheckpoint = useCallback(async (stepIndex: number) => {
    if (!currentSessionId) return;
    setCheckpointLoading(stepIndex);
    try {
      const res = await rejectCheckpoint(currentSessionId, stepIndex);
      if (res.code === 200) {
        setCheckpointSteps(prev => {
          const next = new Set(prev);
          next.delete(stepIndex);
          return next;
        });
      } else {
        message.error(t('dag.checkpoint.rejectFailed', 'Reject failed'));
      }
    } catch {
      message.error(t('dag.checkpoint.rejectFailed', 'Reject failed'));
    } finally {
      setCheckpointLoading(null);
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
