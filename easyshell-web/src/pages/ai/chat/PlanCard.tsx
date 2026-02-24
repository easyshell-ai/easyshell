import React, { useMemo } from 'react';
import { Button, Tag, Tooltip, theme } from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ThunderboltOutlined,
  DesktopOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { StepStatusIcon } from './components';
import DagPlanView from './DagPlanView';
import type { ExecutionPlan } from '../../../types';

interface PlanCardProps {
  plan: ExecutionPlan;
  currentStepIndex: number;
  sessionId: string;
  confirmationStatus?: 'awaiting' | 'confirmed' | 'rejected';
  onConfirm: () => void;
  onReject: () => void;
  confirmLoading?: boolean;
  checkpointSteps?: Set<number>;
  onApproveCheckpoint?: (stepIndex: number) => void;
  onRejectCheckpoint?: (stepIndex: number) => void;
  checkpointLoading?: number | null;
}

const RISK_COLORS: Record<string, string> = {
  LOW: 'green',
  MEDIUM: 'orange',
  HIGH: 'red',
};

const PlanCard: React.FC<PlanCardProps> = ({
  plan, currentStepIndex, confirmationStatus,
  onConfirm, onReject, confirmLoading,
  checkpointSteps, onApproveCheckpoint, onRejectCheckpoint, checkpointLoading,
}) => {
  const { token } = theme.useToken();
  const { t } = useTranslation();

  const hasDagFeatures = useMemo(() =>
    plan.steps.some(s => (s.dependsOn && s.dependsOn.length > 0) || s.condition || s.checkpoint),
    [plan.steps]
  );

  const renderConfirmation = () => (
    <>
      {confirmationStatus === 'awaiting' && (
        <div style={{
          display: 'flex',
          gap: 8,
          marginTop: 12,
          padding: '10px 12px',
          background: 'rgba(250,173,20,0.06)',
          borderRadius: 8,
          border: '1px solid rgba(250,173,20,0.2)',
        }}>
          <Button
            type="primary"
            icon={<CheckCircleOutlined />}
            onClick={onConfirm}
            loading={confirmLoading}
            style={{ background: '#52c41a', borderColor: '#52c41a' }}
          >
            {t('ai.chat.confirm_plan')}
          </Button>
          <Button
            danger
            icon={<CloseCircleOutlined />}
            onClick={onReject}
            loading={confirmLoading}
          >
            {t('ai.chat.reject_plan')}
          </Button>
        </div>
      )}
      {confirmationStatus === 'confirmed' && (
        <div style={{ marginTop: 8, fontSize: 13, fontWeight: 500, color: '#52c41a' }}>
          <CheckCircleOutlined /> {t('ai.chat.plan_confirmed')}
        </div>
      )}
      {confirmationStatus === 'rejected' && (
        <div style={{ marginTop: 8, fontSize: 13, fontWeight: 500, color: '#ff4d4f' }}>
          <CloseCircleOutlined /> {t('ai.chat.plan_rejected')}
        </div>
      )}
    </>
  );

  if (hasDagFeatures) {
    return (
      <div style={{ marginBottom: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
          <span style={{ fontWeight: 600, fontSize: 13 }}>{plan.summary}</span>
          {plan.estimatedRisk && (
            <Tag color={RISK_COLORS[plan.estimatedRisk] || 'default'}>
              {t(`plan.risk.${plan.estimatedRisk}`, plan.estimatedRisk)}
            </Tag>
          )}
        </div>
        <DagPlanView
          plan={plan}
          currentStepIndex={currentStepIndex}
          checkpointSteps={checkpointSteps || new Set()}
          onApproveCheckpoint={onApproveCheckpoint || (() => {})}
          onRejectCheckpoint={onRejectCheckpoint || (() => {})}
          checkpointLoading={checkpointLoading}
        />
        {renderConfirmation()}
      </div>
    );
  }

  return (
    <div style={{ marginBottom: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <span style={{ fontWeight: 600, fontSize: 13 }}>{plan.summary}</span>
        {plan.estimatedRisk && (
          <Tag color={RISK_COLORS[plan.estimatedRisk] || 'default'}>
            {t(`plan.risk.${plan.estimatedRisk}`, plan.estimatedRisk)}
          </Tag>
        )}
      </div>

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
                  <ThunderboltOutlined /> {t('plan.parallelGroup', { group: step.parallelGroup })}
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
                <span style={{ fontSize: 11, color: '#ff4d4f' }}>{step.error}</span>
              )}
              {step.result && !step.error && (
                <span style={{ fontSize: 11, color: token.colorTextSecondary, maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {step.result}
                </span>
              )}
            </div>
          );

          if (step.rollbackHint) {
            return (
              <Tooltip key={step.index} title={`${t('plan.rollbackHint')}: ${step.rollbackHint}`} placement="right">
                {stepContent}
              </Tooltip>
            );
          }
          return <React.Fragment key={step.index}>{stepContent}</React.Fragment>;
        })}
      </div>

      {renderConfirmation()}
    </div>
  );
};

export default PlanCard;
