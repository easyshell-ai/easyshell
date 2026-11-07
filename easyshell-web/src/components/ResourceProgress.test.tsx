import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ResourceProgress from './ResourceProgress';

/**
 * Smoke test for the vitest + jsdom + @testing-library/react setup: antd components
 * render under jsdom so future regression tests can mount real pages.
 */
describe('ResourceProgress', () => {
  it('renders the rounded percentage', () => {
    render(<ResourceProgress value={73.26} />);
    expect(screen.getByText('73.3%')).toBeTruthy();
  });

  it('falls back to 0 for missing values', () => {
    render(<ResourceProgress value={undefined as unknown as number} />);
    expect(screen.getByText('0%')).toBeTruthy();
  });
});
