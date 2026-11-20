import { useMemo } from 'react';
import { RouterProvider } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import antdZhCN from 'antd/locale/zh_CN';
import antdEnUS from 'antd/locale/en_US';
import { useTranslation } from 'react-i18next';
import { ThemeProvider } from './contexts/ThemeContext';
import { useTheme } from './contexts/themeContext';
import ErrorBoundary from './components/ErrorBoundary';
import router from './router';
import { themeByMode } from './theme/themeConfig';
import './i18n';

const ThemedApp: React.FC = () => {
  const { isDark } = useTheme();
  const { i18n } = useTranslation();
  const antdLocale = i18n.language === 'en-US' ? antdEnUS : antdZhCN;
  const themeConfig = useMemo(() => themeByMode(isDark), [isDark]);

  return (
    <ConfigProvider locale={antdLocale} theme={themeConfig}>
      <ErrorBoundary>
        <RouterProvider router={router} />
      </ErrorBoundary>
    </ConfigProvider>
  );
};

function App() {
  return (
    <ThemeProvider>
      <ThemedApp />
    </ThemeProvider>
  );
}

export default App;
