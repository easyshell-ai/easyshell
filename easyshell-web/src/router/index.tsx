import { createBrowserRouter } from 'react-router-dom';
import MainLayout from '../layouts/MainLayout';
import LazyLoad from './LazyLoad';
import {
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
} from './lazyPages';

const router = createBrowserRouter([
  {
    path: '/login',
    element: <LazyLoad><Login /></LazyLoad>,
  },
  {
    path: '/',
    element: <MainLayout />,
    children: [
      {
        index: true,
        element: <LazyLoad><Dashboard /></LazyLoad>,
      },
      {
        path: 'host',
        element: <LazyLoad><Host /></LazyLoad>,
      },
      {
        path: 'host/:agentId',
        element: <LazyLoad><HostDetail /></LazyLoad>,
      },
      {
        path: 'terminal/:agentId',
        element: <LazyLoad><TerminalPage /></LazyLoad>,
      },
      {
        path: 'script',
        element: <LazyLoad><Script /></LazyLoad>,
      },
      {
        path: 'script/new',
        element: <LazyLoad><ScriptWorkbench /></LazyLoad>,
      },
      {
        path: 'script/edit/:id',
        element: <LazyLoad><ScriptWorkbench /></LazyLoad>,
      },
      {
        path: 'task',
        element: <LazyLoad><Task /></LazyLoad>,
      },
      {
        path: 'cluster',
        element: <LazyLoad><Cluster /></LazyLoad>,
      },
      {
        path: 'ai/chat',
        element: <LazyLoad><AiChat /></LazyLoad>,
      },
      {
        path: 'ai/scheduled',
        element: <LazyLoad><AiScheduled /></LazyLoad>,
      },
      {
        path: 'ai/reports',
        element: <LazyLoad><AiReports /></LazyLoad>,
      },
      {
        path: 'ai/approval',
        element: <LazyLoad><AiApproval /></LazyLoad>,
      },
      {
        path: 'audit',
        element: <LazyLoad><Audit /></LazyLoad>,
      },
      {
        path: 'system/users',
        element: <LazyLoad><Users /></LazyLoad>,
      },
      {
        path: 'system/config',
        element: <LazyLoad><SystemConfig /></LazyLoad>,
      },
      {
        path: 'system/ai',
        element: <LazyLoad><AiConfig /></LazyLoad>,
      },
      {
        path: 'system/bot',
        element: <LazyLoad><BotConfig /></LazyLoad>,
      },
      {
        path: 'system/risk',
        element: <LazyLoad><RiskConfig /></LazyLoad>,
      },
      {
        path: 'system/agents',
        element: <LazyLoad><AgentConfig /></LazyLoad>,
      },
      {
        path: 'system/memory',
        element: <LazyLoad><MemoryManagement /></LazyLoad>,
      },
      {
        path: 'system/sop',
        element: <LazyLoad><SopManagement /></LazyLoad>,
      },
    ],
  },
]);

export default router;
