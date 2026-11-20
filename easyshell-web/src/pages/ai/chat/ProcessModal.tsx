import React from 'react';
import { Modal, Empty, theme } from 'antd';
import {
  EyeOutlined,
  ApartmentOutlined,
  SyncOutlined,
  CheckCircleOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { ExecutionPlanView, ToolCallView } from './components';
import type { ProcessData } from './types';

interface ProcessModalProps {
  visible: boolean;
  onClose: () => void;
  processData: ProcessData | null;
}

const ProcessModal: React.FC<ProcessModalProps> = ({ visible, onClose, processData }) => {
  const { t } = useTranslation();
  const { token } = theme.useToken();
  
  return (
  <Modal
    title={
      <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <EyeOutlined style={{ color: token.colorPrimary }} />
        {t('process.title')}
      </span>
    }
    open={visible}
    onCancel={onClose}
    footer={null}
    width={720}
    styles={{ body: { maxHeight: '70vh', overflowY: 'auto' } }}
  >
    {processData && (() => {
      const data = processData;
      const totalSteps = data.plan?.steps.length || 0;
      return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {data.plan && (
            <div>
              <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 8, color: token.colorPrimary }}>
                <ApartmentOutlined style={{ marginRight: 6 }} />
                {t('process.executionPlan')}
              </div>
              <ExecutionPlanView plan={data.plan} currentStepIndex={totalSteps} />
            </div>
          )}
          {data.thinkingLog.length > 0 && (
            <div>
              <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 8, color: token.colorPrimary }}>
                <SyncOutlined style={{ marginRight: 6 }} />
                {t('process.thinkingProcess', { count: data.thinkingLog.length })}
              </div>
              <div style={{
                maxHeight: 240,
                overflowY: 'auto',
                padding: '8px 10px',
                background: token.colorFillTertiary,
                borderRadius: 6,
                borderLeft: `3px solid ${token.colorPrimary}`,
              }}>
                {data.thinkingLog.map((msg, i) => (
                  <div key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: 8, padding: '3px 0', fontSize: 13 }}>
                    <CheckCircleOutlined style={{ color: token.colorSuccess, fontSize: 11, flexShrink: 0, marginTop: 3 }} />
                    <span style={{ color: token.colorTextSecondary }}>{msg}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
          {data.toolCalls.length > 0 && (
            <div>
              <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 8, color: token.colorWarning }}>
                <ThunderboltOutlined style={{ marginRight: 6 }} />
                {t('process.toolCalls', { count: data.toolCalls.length })}
              </div>
              {data.toolCalls.map((tc, i) => (
                <ToolCallView key={i} toolName={tc.toolName} toolArgs={tc.toolArgs} toolResult={tc.toolResult} />
              ))}
            </div>
          )}
          {!data.plan && data.thinkingLog.length === 0 && data.toolCalls.length === 0 && (
            <Empty description={t('process.noData')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
        </div>
      );
    })()}
  </Modal>
);
};

export default ProcessModal;
