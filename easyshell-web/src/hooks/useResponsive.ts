import { useEffect, useState, useCallback } from 'react';

export const breakpoints = {
  xs: 480,
  sm: 576,
  md: 768,
  lg: 992,
  xl: 1200,
  xxl: 1600,
} as const;

export type Breakpoint = keyof typeof breakpoints;

interface ResponsiveState {
  breakpoint: Breakpoint;
  width: number;
  isMobile: boolean;
  isTablet: boolean;
  isDesktop: boolean;
  isXs: boolean;
  isSm: boolean;
  isMd: boolean;
  isLg: boolean;
  isXl: boolean;
  isXxl: boolean;
}

function getBreakpoint(width: number): Breakpoint {
  if (width < breakpoints.xs) return 'xs';
  if (width < breakpoints.sm) return 'sm';
  if (width < breakpoints.md) return 'md';
  if (width < breakpoints.lg) return 'lg';
  if (width < breakpoints.xl) return 'xl';
  return 'xxl';
}

function getResponsiveState(width: number): ResponsiveState {
  const breakpoint = getBreakpoint(width);
  return {
    breakpoint,
    width,
    isMobile: width < breakpoints.md,
    isTablet: width >= breakpoints.md && width < breakpoints.lg,
    isDesktop: width >= breakpoints.lg,
    isXs: width < breakpoints.xs,
    isSm: width >= breakpoints.xs && width < breakpoints.sm,
    isMd: width >= breakpoints.sm && width < breakpoints.md,
    isLg: width >= breakpoints.md && width < breakpoints.lg,
    isXl: width >= breakpoints.lg && width < breakpoints.xl,
    isXxl: width >= breakpoints.xl,
  };
}

export function useResponsive(): ResponsiveState {
  const [state, setState] = useState<ResponsiveState>(() => 
    getResponsiveState(typeof window !== 'undefined' ? window.innerWidth : 1200)
  );

  const handleResize = useCallback(() => {
    setState(getResponsiveState(window.innerWidth));
  }, []);

  useEffect(() => {
    handleResize();
    
    let timeoutId: number;
    const debouncedResize = () => {
      clearTimeout(timeoutId);
      timeoutId = window.setTimeout(handleResize, 100);
    };
    
    window.addEventListener('resize', debouncedResize);
    return () => {
      window.removeEventListener('resize', debouncedResize);
      clearTimeout(timeoutId);
    };
  }, [handleResize]);

  return state;
}

export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => {
    if (typeof window === 'undefined') return false;
    return window.matchMedia(query).matches;
  });

  useEffect(() => {
    const mediaQuery = window.matchMedia(query);
    const handler = (e: MediaQueryListEvent) => setMatches(e.matches);
    
    setMatches(mediaQuery.matches);
    mediaQuery.addEventListener('change', handler);
    return () => mediaQuery.removeEventListener('change', handler);
  }, [query]);

  return matches;
}

export default useResponsive;
