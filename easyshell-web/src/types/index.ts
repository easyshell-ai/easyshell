export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
}

export interface UserVO {
  id: number;
  username: string;
  email: string;
  role: string;
  status: number;
  lastLoginAt: string;
  createdAt: string;
}

export interface UserCreateRequest {
  username: string;
  password: string;
  email?: string;
  role: string;
}

export interface UserUpdateRequest {
  email?: string;
  role?: string;
  status?: number;
}

export interface PasswordResetRequest {
  newPassword: string;
}

export interface SystemConfigVO {
  id: number;
  configKey: string;
  configValue: string;
  description: string;
  configGroup: string;
  updatedAt: string;
}

export interface SystemConfigRequest {
  configKey: string;
  configValue: string;
  description?: string;
  configGroup?: string;
}

export interface Agent {
  id: string;
  hostname: string;
  ip: string;
  os: string;
  arch: string;
  kernel: string;
  cpuModel: string;
  cpuCores: number;
  memTotal: number;
  agentVersion: string;
  status: number;
  lastHeartbeat: string;
  cpuUsage: number;
  memUsage: number;
  diskUsage: number;
  registeredAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface Script {
  id: number;
  name: string;
  description: string;
  content: string;
  scriptType: string;
  isPublic: boolean;
  isTemplate: boolean;
  createdBy: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ScriptRequest {
  name: string;
  description: string;
  content: string;
  scriptType: string;
  isPublic: boolean;
}

export interface Task {
  id: string;
  name: string;
  scriptId: number;
  scriptContent: string;
  scriptType: string;
  timeoutSeconds: number;
  status: number;
  totalCount: number;
  successCount: number;
  failedCount: number;
  createdBy: string;
  startedAt: string;
  finishedAt: string;
  createdAt: string;
  riskLevel?: string;
  approvalStatus?: string;
  source?: string;
  targetAgentIds?: string;
}

export interface TaskCreateRequest {
  name: string;
  scriptId?: number;
  scriptContent?: string;
  agentIds?: string[];
  clusterIds?: number[];
  tagIds?: number[];
  timeoutSeconds: number;
}

export interface Job {
  id: string;
  taskId: string;
  agentId: string;
  status: number;
  exitCode: number;
  output: string;
  startedAt: string;
  finishedAt: string;
}

export interface TaskDetail {
  task: Task;
  jobs: Job[];
}

export interface AgentBriefVO {
  id: string;
  hostname: string;
  ip: string;
  cpuUsage: number;
  memUsage: number;
  diskUsage: number;
  lastHeartbeat: string;
}

export interface DashboardStats {
  totalAgents: number;
  onlineAgents: number;
  offlineAgents: number;
  totalScripts: number;
  totalTasks: number;
  avgCpuUsage: number;
  avgMemUsage: number;
  avgDiskUsage: number;
  recentTasks: Task[];
  onlineAgentDetails: AgentBriefVO[];
  unstableAgents: number;
  todayTasks: number;
  todaySuccessTasks: number;
  todayFailedTasks: number;
  taskSuccessRate: number | null;
  highCpuAgents: number;
  highMemAgents: number;
  highDiskAgents: number;
}

export interface MetricSnapshot {
  id: number;
  agentId: string;
  cpuUsage: number;
  memUsage: number;
  diskUsage: number;
  recordedAt: string;
}

export interface ClusterVO {
  id: number;
  name: string;
  description: string;
  agentCount: number;
  createdBy: number;
  createdAt: string;
}

export interface ClusterDetailVO {
  id: number;
  name: string;
  description: string;
  createdBy: number;
  agents: Agent[];
}

export interface ClusterRequest {
  name: string;
  description?: string;
}

export interface TagVO {
  id: number;
  name: string;
  color: string;
  agentCount: number;
}

export interface TagRequest {
  name: string;
  color?: string;
}

export interface AuditLog {
  id: number;
  userId: number;
  username: string;
  action: string;
  resourceType: string;
  resourceId: string;
  detail: string;
  ip: string;
  result: string;
  createdAt: string;
}

export interface HostProvisionRequest {
  ip: string;
  sshPort: number;
  sshUsername: string;
  sshPassword?: string;
  authType?: 'password' | 'key';
  sshPrivateKey?: string;
  hostName?: string;
  deployNow?: boolean;
}

export interface HostCredentialVO {
  id: number | null;
  ip: string;
  sshPort: number;
  sshUsername: string;
  authType: string;
  hostName: string | null;
  agentId: string | null;
  provisionStatus: string;
  provisionLog: string;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  // Agent-merged fields (from unified list)
  hostname: string | null;
  os: string | null;
  arch: string | null;
  kernel: string | null;
  cpuModel: string | null;
  cpuCores: number | null;
  memTotal: number | null;
  agentVersion: string | null;
  agentStatus: number | null;
  lastHeartbeat: string | null;
  cpuUsage: number | null;
  memUsage: number | null;
  diskUsage: number | null;
  registeredAt: string | null;
}

export type HostInfo = Agent;

export interface AiProviderConfig {
  provider: string;
  apiKey: string;
  baseUrl: string;
  model: string;
  temperature?: number;
  topP?: number;
  maxTokens?: number;
}

export interface AiEmbeddingConfig {
  provider: string;
  model: string;
  apiKey: string;
  baseUrl: string;
}

export interface AiOrchestratorConfig {
  maxIterations: number;
  maxToolCalls: number;
  maxConsecutiveErrors: number;
  sopEnabled: boolean;
  memoryEnabled: boolean;
  dangerousCommands: string[];
  systemPromptOverride: string;
}

export interface AiChannelConfig {
  channel: string;
  enabled: boolean;
  settings: Record<string, string>;
}


export interface AiChannelContextConfig {
  contextMode: string;
  sessionTimeout: number;
  defaultProvider: string;
  defaultModel: string;
}

export interface AiConfigVO {
  enabled: boolean;
  defaultProvider: string;
  providers: Record<string, AiProviderConfig>;
  quota: {
    dailyLimit: number;
    maxTokens: number;
    chatTimeout: number;
  };
  channels: Record<string, AiChannelConfig>;
  channelContext?: AiChannelContextConfig;
  embedding?: AiEmbeddingConfig;
  orchestrator?: AiOrchestratorConfig;
}

export interface AiConfigSaveRequest {
  enabled: boolean;
  defaultProvider?: string;
  providers?: Record<string, { apiKey?: string; baseUrl?: string; model?: string; temperature?: number; topP?: number; maxTokens?: number }>;
  quota?: { dailyLimit?: number; maxTokens?: number; chatTimeout?: number };
  channels?: Record<string, { enabled?: boolean; settings?: Record<string, string> }>;
  channelContext?: { contextMode?: string; sessionTimeout?: number; defaultProvider?: string; defaultModel?: string };
  embedding?: { provider?: string; model?: string; apiKey?: string; baseUrl?: string };
  orchestrator?: { maxIterations?: number; maxToolCalls?: number; maxConsecutiveErrors?: number; sopEnabled?: boolean; memoryEnabled?: boolean; systemPromptOverride?: string };
}

export interface AiTestRequest {
  provider: string;
  apiKey?: string;
  baseUrl?: string;
  model?: string;
}

export interface AiTestResult {
  success: boolean;
  message: string;
  responseTimeMs: number;
  modelInfo: string;
}

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'BANNED';

export interface CommandRisk {
  command: string;
  level: RiskLevel;
  reason: string;
}

export interface RiskAssessment {
  overallRisk: RiskLevel;
  commandRisks: CommandRisk[];
  bannedMatches: string[];
  autoExecutable: boolean;
  explanation: string;
}

export interface AiRiskRulesVO {
  bannedCommands: string[];
  highCommands: string[];
  lowCommands: string[];
}

export interface AiRiskRulesSaveRequest {
  bannedCommands?: string[];
  highCommands?: string[];
  lowCommands?: string[];
}

export interface AiRiskAssessRequest {
  scriptContent: string;
}

export interface AiChatSession {
  id: string;
  title: string;
  provider: string;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface AiChatMessage {
  id: number;
  sessionId: string;
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  toolName?: string;
  processData?: string;
  createdAt: string;
}

export interface AiChatRequest {
  sessionId?: string;
  message: string;
  provider?: string;
  model?: string;
  enableTools?: boolean;
  targetAgentIds?: string[];
}

export interface AiChatResponse {
  sessionId: string;
  content: string;
}

export type AgentEventType =
  | 'SESSION'
  | 'PLAN'
  | 'STEP_START'
  | 'THINKING'
  | 'TOOL_CALL'
  | 'TOOL_RESULT'
  | 'CONTENT'
  | 'STEP_COMPLETE'
  | 'APPROVAL'
  | 'DONE'
  | 'ERROR'
  // Phase 1 agentic additions
  | 'ITERATION_START'
  | 'REFLECTION'
  | 'SUBTASK_STARTED'
  | 'SUBTASK_COMPLETED'
  | 'SUBTASK_PROGRESS'
  // Phase 2 planning additions
  | 'PLAN_AWAIT_CONFIRMATION'
  | 'PLAN_CONFIRMED'
  | 'PLAN_REJECTED'
  | 'STEP_RETRY'
  | 'PLAN_SUMMARY'
  | 'REVIEW_START'
  | 'REVIEW_COMPLETE'
  | 'PARALLEL_START'
  | 'PARALLEL_PROGRESS'
  | 'PARALLEL_COMPLETE'
  // Phase 3 advanced capabilities additions
  | 'MEMORY_RETRIEVED'
  | 'SOP_MATCHED'
  | 'SOP_APPLIED'
  | 'TASK_CLASSIFIED'
  | 'STEP_CHECKPOINT'
  | 'STEP_CONDITION_EVAL'
  | 'VARIABLE_SET';

export interface PlanStep {
  index: number;
  description: string;
  agent: string;
  status: 'pending' | 'running' | 'completed' | 'failed' | 'skipped' | 'waiting_approval';
  tools?: string[];
  hosts?: string[];
  parallelGroup?: number;
  rollbackHint?: string;
  result?: string;
  error?: string;
  dependsOn?: number[];
  condition?: string;
  checkpoint?: boolean;
  outputVar?: string;
  timeoutSec?: number;
  onFailure?: string;
  inputVars?: Record<string, string>;
}

export interface ExecutionPlan {
  summary: string;
  steps: PlanStep[];
  requiresConfirmation?: boolean;
  estimatedRisk?: string;
}

export interface AgentEvent {
  type: AgentEventType;
  agent?: string;
  stepIndex?: number;
  stepDescription?: string;
  content?: string;
  toolName?: string;
  toolArgs?: string;
  toolResult?: string;
  plan?: ExecutionPlan;
  sessionId?: string;
  approvalData?: Record<string, unknown>;
  metadata?: Record<string, string>;
  maxIterations?: number;
  iterationNumber?: number;
  // Phase 2 additions
  iteration?: number;
  parallelGroup?: number;
  totalTasks?: number;
  completedTasks?: number;
}

// Phase 3 — Memory and SOP types
export interface AiSessionSummary {
  id: number;
  sessionId: string;
  userId: number;
  summary: string;
  keyOperations: string;
  hostsInvolved: string;
  servicesInvolved: string;
  outcome: string;
  tags: string;
  embeddingId: string;
  createdAt: string;
  updatedAt: string;
}

export interface AiSopTemplate {
  id: number;
  title: string;
  description: string;
  stepsJson: string;
  triggerPattern: string;
  category: string;
  confidence: number;
  enabled: boolean;
  usageCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface AiSopTemplateRequest {
  title?: string;
  description?: string;
  stepsJson?: string;
  triggerPattern?: string;
  category?: string;
  enabled?: boolean;
}

export interface HostSoftwareInventory {
  id: number;
  agentId: string;
  softwareName: string;
  softwareVersion: string | null;
  softwareType: string;
  processId: number | null;
  listeningPorts: string | null;
  detectedAt: string;
  isDockerContainer: boolean;
  dockerImage: string | null;
  dockerContainerName: string | null;
  dockerContainerStatus: string | null;
  dockerPorts: string | null;
}

export interface AiScheduledTask {
  id: number;
  name: string;
  description: string;
  taskType: string;
  cronExpression: string;
  targetType: string;
  targetIds: string;
  scriptTemplate: string | null;
  aiPrompt: string | null;
  enabled: boolean;
  lastRunAt: string | null;
  nextRunAt: string | null;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
  notifyStrategy: string | null;
  notifyChannels: string | null;
  notifyAiPrompt: string | null;
}

export interface AiScheduledTaskRequest {
  name: string;
  description?: string;
  taskType: string;
  cronExpression: string;
  targetType: string;
  targetIds: string;
  scriptTemplate?: string;
  aiPrompt?: string;
  enabled?: boolean;
  notifyStrategy?: string;
  notifyChannels?: string;
  notifyAiPrompt?: string;
}

export interface AiInspectReport {
  id: number;
  scheduledTaskId: number;
  taskType: string;
  taskName: string;
  targetSummary: string;
  scriptOutput: string;
  aiAnalysis: string | null;
  status: string;
  createdBy: number;
  createdAt: string;
}

export interface AiAlertRequest {
  alertDescription: string;
  agentId?: string;
  alertSource?: string;
  severity?: string;
}

export interface AiAlertAnalysis {
  analysis: string;
  suggestedAction: string;
  riskLevel: string;
  autoFixAvailable: boolean;
  autoFixScript: string | null;
}

export interface BuiltInTemplate {
  type: string;
  name: string;
  description: string;
  script: string;
  aiPrompt: string;
}
