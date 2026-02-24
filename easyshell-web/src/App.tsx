import { RouterProvider } from 'react-router-dom';
import { ConfigProvider, theme } from 'antd';
import antdZhCN from 'antd/locale/zh_CN';
import antdEnUS from 'antd/locale/en_US';
import { useTranslation } from 'react-i18next';
import { ThemeProvider, useTheme } from './contexts/ThemeContext';
import ErrorBoundary from './components/ErrorBoundary';
import router from './router';
import './i18n';

const ThemedApp: React.FC = () => {
  const { isDark } = useTheme();
  const { i18n } = useTranslation();
  const antdLocale = i18n.language === 'en-US' ? antdEnUS : antdZhCN;

  return (
    <ConfigProvider
      locale={antdLocale}
      theme={{
        algorithm: isDark ? theme.darkAlgorithm : theme.defaultAlgorithm,
        token: {
          colorPrimary: '#2563eb',
          fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
          fontSize: 14,
          borderRadius: 10,
          controlHeight: 36,
          wireframe: false,
          colorBgLayout: isDark ? '#0a0a0b' : '#f5f5f7',
          colorBgContainer: isDark ? '#141416' : '#ffffff',
          colorBorder: isDark ? '#2a2a2e' : '#e8e8ec',
          colorBorderSecondary: isDark ? '#1f1f23' : '#f0f0f3',
        },
        components: {
          Card: {
            paddingLG: 20,
            borderRadiusLG: 12,
          },
          Table: {
            borderRadiusLG: 12,
            headerBg: 'transparent',
            headerColor: isDark ? '#a1a1aa' : '#71717a',
            fontSize: 13,
            cellPaddingBlock: 12,
            cellPaddingInline: 16,
          },
          Button: {
            borderRadius: 8,
            controlHeight: 36,
          },
          Input: {
            borderRadius: 8,
          },
          Select: {
            borderRadius: 8,
          },
          Modal: {
            borderRadiusLG: 14,
          },
          Drawer: {
            borderRadiusLG: 14,
          },
          Menu: {
            itemBorderRadius: 8,
            subMenuItemBorderRadius: 8,
            itemMarginBlock: 2,
            itemMarginInline: 4,
            itemPaddingInline: 12,
            itemHeight: 40,
            iconMarginInlineEnd: 10,
            fontSize: 14,
          },
        },
      }}
    >
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
