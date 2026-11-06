import { Suspense } from 'react';
import { Spin } from 'antd';

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

export default LazyLoad;
