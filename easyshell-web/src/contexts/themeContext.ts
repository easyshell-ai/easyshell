import { createContext, useContext } from 'react';

export interface ThemeContextType {
  isDark: boolean;
  toggleTheme: () => void;
  resetTheme: () => void;
}

const ThemeContext = createContext<ThemeContextType>({
  isDark: true,
  toggleTheme: () => {},
  resetTheme: () => {},
});

export const useTheme = (): ThemeContextType => {
  const context = useContext(ThemeContext);
  return context;
};

export default ThemeContext;
