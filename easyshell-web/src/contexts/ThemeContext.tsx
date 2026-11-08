import { useEffect, useState, type ReactNode } from 'react';
import ThemeContext from './themeContext';
import { applySemanticColors } from '../theme/semantics';

const THEME_MODE_KEY = 'easyshell-theme-mode';
const LEGACY_THEME_KEY = 'easyshell-theme';

/**
 * 老用户一次性迁移（设计文档 §8.2-W1 / C-11）：旧键 easyshell-theme 作废，
 * 不继承旧值——默认深色（运维场景）。只想回默认的用户用顶栏"重置为默认主题"。
 */
function migrateLegacyTheme(): void {
  if (localStorage.getItem(LEGACY_THEME_KEY) !== null) {
    localStorage.removeItem(LEGACY_THEME_KEY);
  }
}

export const ThemeProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [isDark, setIsDark] = useState<boolean>(() => {
    migrateLegacyTheme();
    return localStorage.getItem(THEME_MODE_KEY) !== 'light';
  });

  useEffect(() => {
    localStorage.setItem(THEME_MODE_KEY, isDark ? 'dark' : 'light');
    document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
    applySemanticColors(isDark ? 'dark' : 'light');
  }, [isDark]);

  const toggleTheme = () => setIsDark((prev) => !prev);
  const resetTheme = () => setIsDark(true);

  return (
    <ThemeContext.Provider value={{ isDark, toggleTheme, resetTheme }}>
      {children}
    </ThemeContext.Provider>
  );
};
