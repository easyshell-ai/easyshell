import { lazy } from 'react';

const Login = lazy(() => import('../pages/login'));
const Dashboard = lazy(() => import('../pages/dashboard'));
const Host = lazy(() => import('../pages/host'));
const HostDetail = lazy(() => import('../pages/host/detail'));
const Script = lazy(() => import('../pages/script'));
const ScriptWorkbench = lazy(() => import('../pages/script/ScriptWorkbench'));
const Task = lazy(() => import('../pages/task'));
const Cluster = lazy(() => import('../pages/cluster'));
const Audit = lazy(() => import('../pages/audit'));
const TerminalPage = lazy(() => import('../pages/terminal'));
const Users = lazy(() => import('../pages/system/users'));
const HostPermissions = lazy(() => import('../pages/system/HostPermissions'));
const SystemConfig = lazy(() => import('../pages/system/config'));
const AiConfig = lazy(() => import('../pages/system/ai'));
const BotConfig = lazy(() => import('../pages/system/bot'));
const RiskConfig = lazy(() => import('../pages/system/risk'));
const AgentConfig = lazy(() => import('../pages/system/agents'));
const MemoryManagement = lazy(() => import('../pages/system/memory'));
const SopManagement = lazy(() => import('../pages/system/sop'));
const AiChat = lazy(() => import('../pages/ai/chat'));
const AiScheduled = lazy(() => import('../pages/ai/scheduled'));
const AiReports = lazy(() => import('../pages/ai/reports'));
const AiApproval = lazy(() => import('../pages/ai/approval'));
const WorkflowList = lazy(() => import('../pages/workflow'));
const WorkflowEditor = lazy(() => import('../pages/workflow/editor'));
const WorkflowRunList = lazy(() => import('../pages/workflow/run'));
const WorkflowRunMonitor = lazy(() => import('../pages/workflow/run/monitor'));
const WorkflowApprovals = lazy(() => import('../pages/workflow/approvals'));

export {
  Login,
  Dashboard,
  Host,
  HostDetail,
  Script,
  ScriptWorkbench,
  Task,
  Cluster,
  Audit,
  TerminalPage,
  Users,
  HostPermissions,
  SystemConfig,
  AiConfig,
  BotConfig,
  RiskConfig,
  AgentConfig,
  MemoryManagement,
  SopManagement,
  AiChat,
  AiScheduled,
  AiReports,
  AiApproval,
  WorkflowList,
  WorkflowEditor,
  WorkflowRunList,
  WorkflowRunMonitor,
  WorkflowApprovals,
};
