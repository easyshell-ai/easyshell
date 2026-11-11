import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { TemplateGallery } from './TemplateGallery';
import { templateById } from './index';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string, options?: { defaultValue?: string }) => options?.defaultValue ?? key }),
}));

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false, media: query, onchange: null,
      addListener: () => {}, removeListener: () => {},
      addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false,
    }),
  });
});

afterEach(cleanup);

describe('TemplateGallery', () => {
  it('identifies the real AI call and its prerequisites without changing existing labels', () => {
    const onApply = vi.fn();
    const definition = templateById('aiReview')!.build();
    render(<TemplateGallery open definition={{ ...definition, nodes: [] }} onCancel={vi.fn()} onApply={onApply} />);

    const aiCard = screen.getByTestId('workflow-template-aiReview').closest('.ant-card') as HTMLElement;
    expect(within(aiCard).getByText('Real AI call')).toBeTruthy();
    expect(aiCard.textContent).toContain('Configure the default AI provider and model before running');
    expect(aiCard.textContent).toContain('Input is sent to the configured AI provider; do not enter sensitive information');
    expect(aiCard.textContent).not.toContain('workflow.template.simulated');
    expect(aiCard.textContent).not.toContain('workflow.template.realSideEffects');

    for (const id of ['disk', 'health']) {
      const card = screen.getByTestId(`workflow-template-${id}`).closest('.ant-card') as HTMLElement;
      expect(card.textContent).toContain('workflow.template.simulated');
    }
    const restart = screen.getByTestId('workflow-template-restart').closest('.ant-card') as HTMLElement;
    expect(restart.textContent).toContain('workflow.template.realSideEffects');
    expect(restart.textContent).toContain('This template can restart a real service');

    fireEvent.click(screen.getByTestId('workflow-template-aiReview'));
    expect(onApply).toHaveBeenCalledWith(definition, 'aiReview');
  });
});
