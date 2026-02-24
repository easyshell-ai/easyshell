export const PROVIDER_OPTIONS = [
  { value: 'default', label: 'chat.providerDefault' },
  { value: 'openai', label: 'OpenAI' },
  { value: 'anthropic', label: 'Anthropic' },
  { value: 'gemini', label: 'Gemini' },
  { value: 'ollama', label: 'Ollama' },
  { value: 'github-copilot', label: 'GitHub Copilot' },
];

export const MODEL_OPTIONS: Record<string, { value: string; label: string }[]> = {
  default: [{ value: '', label: 'chat.defaultModel' }],
  openai: [
    { value: '', label: 'chat.defaultModel' },
    { value: 'gpt-4o', label: 'GPT-4o' },
    { value: 'gpt-4o-mini', label: 'GPT-4o Mini' },
    { value: 'gpt-4-turbo', label: 'GPT-4 Turbo' },
    { value: 'gpt-3.5-turbo', label: 'GPT-3.5 Turbo' },
    { value: 'qwen3-max', label: 'Qwen3 Max' },
    { value: 'qwen3-plus', label: 'Qwen3 Plus' },
    { value: 'qwen-turbo-latest', label: 'Qwen Turbo' },
    { value: 'deepseek-v3', label: 'DeepSeek V3' },
    { value: 'deepseek-r1', label: 'DeepSeek R1' },
  ],
  anthropic: [
    { value: '', label: 'chat.defaultModel' },
    { value: 'claude-sonnet-4-20250514', label: 'Claude Sonnet 4' },
    { value: 'claude-3-5-sonnet-20241022', label: 'Claude 3.5 Sonnet' },
    { value: 'claude-3-haiku-20240307', label: 'Claude 3 Haiku' },
  ],
  gemini: [
    { value: '', label: 'chat.defaultModel' },
    { value: 'gemini-2.0-flash', label: 'Gemini 2.0 Flash' },
    { value: 'gemini-1.5-pro', label: 'Gemini 1.5 Pro' },
  ],
  ollama: [
    { value: '', label: 'chat.defaultModel' },
    { value: 'llama3', label: 'Llama 3' },
    { value: 'qwen2', label: 'Qwen 2' },
    { value: 'mistral', label: 'Mistral' },
  ],
  'github-copilot': [
    { value: '', label: 'chat.defaultModel' },
    { value: 'gpt-4o', label: 'GPT-4o' },
    { value: 'gpt-4o-mini', label: 'GPT-4o Mini' },
    { value: 'claude-sonnet-4-20250514', label: 'Claude Sonnet 4' },
    { value: 'claude-3.5-sonnet', label: 'Claude 3.5 Sonnet' },
    { value: 'o1', label: 'o1' },
    { value: 'o3-mini', label: 'o3-mini' },
  ],
};

export const SUGGESTED_PROMPTS = [
  { icon: '🖥️', text: 'chat.prompt1' },
  { icon: '📋', text: 'chat.prompt2' },
  { icon: '💊', text: 'chat.prompt3' },
  { icon: '🔒', text: 'chat.prompt4' },
];
