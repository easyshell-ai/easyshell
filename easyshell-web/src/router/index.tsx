import { lazy, Suspense } from 'react';
import { createBrowserRouter } from 'react-router-dom';
import { Spin } from 'antd';
import MainLayout from '../layouts/MainLayout';

const Login = lazy(() => import('../pages/login'));
const Dashboard = lazy(() => import('../pages/dashboard'));
const Host = lazy(() => import('../pages/host'));
const HostDetail = lazy(() => import('../pages/host/detail'));
const Script = lazy(() => import('../pages/script'));
const Task = lazy(() => import('../pages/task'));
const Cluster = lazy(() => import('../pages/cluster'));
const Audit = lazy(() => import('../pages/audit'));
const TerminalPage = lazy(() => import('../pages/terminal'));
const Users = lazy(() => import('../pages/system/users'));
const SystemConfig = lazy(() => import('../pages/system/config'));
const AiConfig = lazy(() => import('../pages/system/ai'));
const RiskConfig = lazy(() => import('../pages/system/risk'));
const AgentConfig = lazy(() => import('../pages/system/agents'));
const MemoryManagement = lazy(() => import('../pages/system/memory'));
const SopManagement = lazy(() => import('../pages/system/sop'));
const AiChat = lazy(() => import('../pages/ai/chat'));
const AiScheduled = lazy(() => import('../pages/ai/scheduled'));
const AiReports = lazy(() => import('../pages/ai/reports'));
const AiApproval = lazy(() => import('../pages/ai/approval'));

const LazyLoad = ({ children }: { children: React.ReactNode }) => (
  <Suspense
    fallback={
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%', minHeight: 200 }}>
        <Spin size="large" />
      </div>
    }
  >
    {children}
  </Suspense>
);

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
