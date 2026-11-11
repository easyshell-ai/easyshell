// Only round-trip the exact subset represented by the table. Everything else stays raw.
export function editableSchema(raw: string): { properties: Record<string, { type: string }>; required: string[]; additionalProperties: false } | null {
  if (!raw.trim()) return { properties: {}, required: [], additionalProperties: false };
  try {
    const s = JSON.parse(raw);
    if (!s || typeof s !== 'object' || Array.isArray(s) || s.type !== 'object' ||
        Object.keys(s).some((k) => !['type', 'properties', 'required', 'additionalProperties'].includes(k)) ||
        !s.properties || typeof s.properties !== 'object' || Array.isArray(s.properties) ||
        (s.required !== undefined && (!Array.isArray(s.required) || s.required.some((v: unknown) => typeof v !== 'string'))) ||
        s.additionalProperties !== false) return null;
    if (Object.entries(s.properties).some(([, value]) => !value || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).some((k) => k !== 'type') || !['string', 'number', 'integer', 'boolean'].includes((value as { type: string }).type))) return null;
    if ((s.required || []).some((name: string) => !Object.prototype.hasOwnProperty.call(s.properties, name)) || new Set(s.required || []).size !== (s.required || []).length) return null;
    return { properties: s.properties, required: s.required || [], additionalProperties: false };
  } catch { return null; }
}
