import { describe, expect, it, vi } from 'vitest';
import request from './request';
import { confirmPlan, rejectPlan } from './ai';
import en from '../i18n/locales/en-US.json';
import zh from '../i18n/locales/zh-CN.json';

vi.mock('./request', () => ({ default: { post: vi.fn() } }));

describe('plan confirmation contract', () => {
  it('sends the displayed plan identifier for either decision', () => {
    confirmPlan('session', 'plan-one');
    rejectPlan('session', 'plan-two');
    expect(request.post).toHaveBeenCalledWith('/v1/ai/chat/sessions/session/plan/confirm', { planId: 'plan-one' });
    expect(request.post).toHaveBeenCalledWith('/v1/ai/chat/sessions/session/plan/reject', { planId: 'plan-two' });
  });

  it('has a generic decision error in both supported languages', () => {
    expect(en.common.error).toBeTruthy();
    expect(zh.common.error).toBeTruthy();
  });
});
