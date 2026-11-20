import React, { useMemo, useState } from 'react';
import { Button, Collapse, Space, Typography } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { WorkflowDefinition } from '../types';

const { Text } = Typography;

/** 定义 JSON 折叠查看（只读）：当前画布状态的权威序列化形态，与 PUT 草稿的 body 同源 */
const DefinitionJsonView: React.FC<{ definition: WorkflowDefinition }> = ({ definition }) => {
  const { t } = useTranslation();
  const [active, setActive] = useState<string[]>([]);
  const json = useMemo(() => JSON.stringify(definition, null, 2), [definition]);

  return (
    <Collapse
      activeKey={active}
      onChange={(keys) => setActive(keys as string[])}
      items={[
        {
          key: 'definition',
          label: (
            <Space>
              <Text strong>{t('workflow.json.title')}</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {t('workflow.json.meta', {
                  nodes: definition.nodes?.length ?? 0,
                  edges: definition.edges?.length ?? 0,
                  version: definition.schemaVersion,
                })}
              </Text>
            </Space>
          ),
          extra: (
            <Button
              type="link"
              size="small"
              icon={<CopyOutlined />}
              onClick={(event) => {
                event.stopPropagation();
                void navigator.clipboard?.writeText(json).catch(() => undefined);
              }}
            >
              {t('common.copy')}
            </Button>
          ),
          children: (
            <pre className="wf-definition-json" data-testid="definition-json">
              {json}
            </pre>
          ),
        },
      ]}
    />
  );
};

export default DefinitionJsonView;
