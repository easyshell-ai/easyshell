/**
 * 草稿 localStorage 脱敏（设计 §4.3 / B2 #16）。
 *
 * 与 easyshell-server 的 ai/security/SensitiveDataFilter.java 同规则正则的 JS 镜像：
 * M1 没有 secret 类型的变量，但用户完全可能把凭据写进 scriptContent / parameters，
 * 而草稿是按 `wf-draft-{id}` 落盘的。单测与 Java 的行为逐条对齐（见 *.test.ts）。
 */

const MASK = '***';

interface Rule {
  name: string;
  pattern: RegExp;
  replace: (groups: RegExpMatchArray) => string;
}

const RULES: Rule[] = [
  {
    name: 'key-value',
    // password=xxx / passwd:xxx / pwd=xxx / secret / token / api_key / access-key / auth_token
    pattern: /(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|auth[_-]?token)\s*[=:]\s*['"]?([^\s'"&;,}{\]]+)/gi,
    replace: (g) => `${g[1]}=${MASK}`,
  },
  {
    name: 'bearer',
    pattern: /(Bearer)\s+([A-Za-z0-9\-._~+/]+=*)/g,
    replace: (g) => `${g[1]} ${MASK}`,
  },
  {
    name: 'openai-key',
    pattern: /(sk-[A-Za-z0-9]{20,})/g,
    replace: () => `sk-${MASK}`,
  },
  {
    name: 'aws-key',
    pattern: /(AKIA[A-Z0-9]{16})/g,
    replace: () => `AKIA${MASK}`,
  },
  {
    name: 'private-key',
    pattern: /-----BEGIN\s+(RSA\s+)?PRIVATE\s+KEY-----[\s\S]*?-----END\s+(RSA\s+)?PRIVATE\s+KEY-----/g,
    replace: () => `-----BEGIN PRIVATE KEY-----\n${MASK}\n-----END PRIVATE KEY-----`,
  },
  {
    name: 'connection-string',
    pattern: /(mysql|postgresql|postgres|jdbc):\/\/[^:]+:([^@\s]+)@/gi,
    replace: (g) => {
      const full = g[0];
      const colonIdx = full.indexOf('://');
      if (colonIdx < 0) return full;
      const prefix = full.slice(0, colonIdx + 3);
      const rest = full.slice(colonIdx + 3);
      const userColonIdx = rest.indexOf(':');
      const atIdx = rest.indexOf('@');
      if (userColonIdx >= 0 && atIdx > userColonIdx) {
        return `${prefix}${rest.slice(0, userColonIdx + 1)}${MASK}@`;
      }
      return full;
    },
  },
  {
    name: 'long-token',
    pattern: /(api[_-]?key|secret[_-]?key|private[_-]?key)\s*[=:]\s*['"]?([A-Za-z0-9+/=]{32,})/gi,
    replace: (g) => `${g[1]}=${MASK}`,
  },
  {
    name: 'export-env',
    pattern: /export\s+(\w*(PASSWORD|SECRET|TOKEN|KEY|CREDENTIAL)\w*)\s*=\s*['"]?([^\s'"]+)/gi,
    replace: (g) => `export ${g[1]}=${MASK}`,
  },
];

/** 对单段文本脱敏（与 Java filter() 等价：按规则顺序各跑一遍全局替换） */
export function filterSensitive(text: string): string {
  if (!text) return text;
  let filtered = text;
  for (const rule of RULES) {
    filtered = filtered.replace(rule.pattern, (...args: unknown[]) => {
      // replace 回调签名 (match, p1..pn, offset, string)，丢掉后两个就是匹配组数组
      const groups = args.slice(0, -2) as unknown as RegExpMatchArray;
      return rule.replace(groups);
    });
  }
  return filtered;
}

/** 整个 definition 落盘前的脱敏：序列化后扫一遍，凭据不会以任何形态进 localStorage */
export function sanitizeDefinitionForStorage(definition: unknown): string {
  return filterSensitive(JSON.stringify(definition));
}

export const SENSITIVE_RULE_NAMES = RULES.map((r) => r.name);
