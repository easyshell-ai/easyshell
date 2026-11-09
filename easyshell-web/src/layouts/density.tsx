import type { ReactNode } from 'react';
import { ConfigProvider } from 'antd';
import { DENSITY } from './layoutConstants';

/**
 * 紧凑密度作用域（设计文档 §4.10.2 的"覆盖机制"）。
 *
 * 为什么是嵌套 ConfigProvider 而不是 CSS 变量：AntD 6 的 cssinjs 把**组件 token**
 * 声明在组件自己的根类上（实测选择器形如 `:where(.css-hash).es.ant-table`），
 * 祖先类里写 `--es-table-cell-padding-block` 会被元素上的直接声明压掉，继承不过来。
 * 只有让子树重新注册一份组件 token 才能改密度——这正是 §4.10.2 规定的做法。
 *
 * 嵌套的 theme 只给 token/components，不碰 algorithm 与 cssVar：antd 的 useTheme 会把
 * 父级 cssVar（`{key:'es',prefix:'es'}`）继承下来，所以子树里 `--es-*` 仍是同一套命名，
 * 不存在第二套配色。
 */
export const DensityScope: React.FC<{ compact?: boolean; children: ReactNode }> = ({
  compact = false,
  children,
}) => (
  <ConfigProvider theme={compact ? DENSITY.compact : undefined}>{children}</ConfigProvider>
);

export default DensityScope;
