import { createContext, useContext } from 'react';

export interface ThemeContextType {
  isDark: boolean;
  toggleTheme: () => void;
}

const ThemeContext = createContext<ThemeContextType>({
  isDark: false,
  toggleTheme: () => {},
});

export const useTheme = (): ThemeContextType => {
  const context = useContext(ThemeContext);
  return context;
};

export default ThemeContext;
