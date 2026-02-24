import React from 'react';
import { Progress } from 'antd';
import { getResourceColor } from '../utils/status';

interface ResourceProgressProps {
  /** Resource usage percentage (0-100) */
  value: number;
  /** Progress display size */
  size?: 'small' | 'default';
  /** Override color (otherwise auto-calculated from thresholds) */
  color?: string;
}

/**
 * Resource usage progress bar with automatic color thresholds.
 * > 80% → red, > 60% → yellow, else → green
 */
const ResourceProgress: React.FC<ResourceProgressProps> = ({
  value,
  size = 'small',
  color,
}) => {
  const val = Number((value ?? 0).toFixed(1));
  return (
    <Progress
      percent={val}
      size={size}
      strokeColor={color ?? getResourceColor(val)}
    />
  );
};

export default ResourceProgress;
