// scripts/verify-theme-tokens.mjs —— src/theme/*.ts 的全部 hex 必须可溯源（退出码 0 = 全部有来源）
// 设计文档 frontend-redesign-design-v3.md §3.2.1：hex 只能来自 8 套 DESIGN.md 或 §3.2 的 18 个推导值。
// 用法（在 easyshell-web/ 下）：node scripts/verify-theme-tokens.mjs
//   DESIGN_MD_DIRS=/path/to/design-md node scripts/verify-theme-tokens.mjs   # 换 DESIGN.md 目录
import fs from 'node:fs';
import path from 'node:path';

const DESIGN_DIRS = process.env.DESIGN_MD_DIRS ? process.env.DESIGN_MD_DIRS.split(',') : ['/tmp/awesome-design-md/design-md'];

const designHexes = new Set();
for (const dir of DESIGN_DIRS) {
  if (!fs.existsSync(dir)) continue;
  for (const slug of fs.readdirSync(dir)) {
    const f = path.join(dir, slug, 'DESIGN.md');
    if (!fs.existsSync(f)) continue;
    for (const m of fs.readFileSync(f, 'utf8').matchAll(/#[0-9a-fA-F]{6}\b/g)) designHexes.add(m[0].toLowerCase());
  }
}

const DERIVED = new Set(['#202020', '#f0f0f0', '#1b1c1e', '#1c1e30', '#252948', '#99a3ff', '#7eddaf', '#ffd15c',
  '#79cdff', '#ff8484', '#3e4142', '#e2e2e2', '#00673b', '#0655b8', '#8c4708', '#a20000', '#f0689f', '#1f8d74']); // §3.2 的 18 个

const files = [];
const walk = (p) => {
  for (const e of fs.readdirSync(p, { withFileTypes: true })) {
    const full = path.join(p, e.name);
    if (e.isDirectory()) walk(full);
    else if (/\.(ts|tsx|css)$/.test(e.name)) files.push(full);
  }
};
for (const g of ['src/theme']) if (fs.existsSync(g)) walk(g);

const ALLOWED = new Set([...designHexes, ...DERIVED]);
const bad = [];
let total = 0;
for (const f of files) {
  for (const m of fs.readFileSync(f, 'utf8').matchAll(/#[0-9a-fA-F]{6}\b/g)) {
    total++;
    if (!ALLOWED.has(m[0].toLowerCase())) bad.push(`${f}: ${m[0]}`);
  }
}

console.log(`DESIGN.md 源值 ${designHexes.size} + 推导值 ${DERIVED.size} = 白名单 ${ALLOWED.size}；扫描 ${files.length} 文件 / ${total} 处 hex`);
if (bad.length) {
  console.error('无来源 hex:\n' + bad.join('\n'));
  process.exit(1);
}
console.log('OK：全部 hex 可溯源');
