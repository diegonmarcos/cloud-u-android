// The .json viewer's model: parse with a readable error, flatten to visible tree rows.
// The text editor and the tree share one source string; the tree is only a view.

export type JsonKind = 'object' | 'array' | 'string' | 'number' | 'boolean' | 'null';

export interface JsonRow {
  /** JSON Pointer (RFC 6901) of this value; '' is the root */
  pointer: string;
  key: string;
  depth: number;
  kind: JsonKind;
  /** the scalar text, or "{3}" / "[2]" for containers */
  preview: string;
  expandable: boolean;
  expanded: boolean;
}

export type JsonParse = { ok: true; value: unknown } | { ok: false; error: string };

export function parseJson(text: string): JsonParse {
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

export const kindOf = (v: unknown): JsonKind =>
  v === null ? 'null' : Array.isArray(v) ? 'array' : (typeof v as JsonKind);

const esc = (k: string) => k.replace(/~/g, '~0').replace(/\//g, '~1');

export function jsonRows(value: unknown, expanded: ReadonlySet<string>, maxRows = 5000): JsonRow[] {
  const rows: JsonRow[] = [];
  const visit = (v: unknown, key: string, pointer: string, depth: number) => {
    if (rows.length >= maxRows) return;
    const kind = kindOf(v);
    const container = kind === 'object' || kind === 'array';
    const open = container && (pointer === '' || expanded.has(pointer));
    const size = container ? (kind === 'array' ? (v as unknown[]).length : Object.keys(v as object).length) : 0;
    rows.push({
      pointer,
      key,
      depth,
      kind,
      preview: container ? (kind === 'array' ? `[${size}]` : `{${size}}`) : kind === 'string' ? JSON.stringify(v) : String(v),
      expandable: container && size > 0,
      expanded: open,
    });
    if (!open) return;
    if (kind === 'array') (v as unknown[]).forEach((c, i) => visit(c, String(i), `${pointer}/${i}`, depth + 1));
    else for (const [k, c] of Object.entries(v as Record<string, unknown>)) visit(c, k, `${pointer}/${esc(k)}`, depth + 1);
  };
  visit(value, '$', '', 0);
  return rows;
}

export const formatJson = (text: string, indent = 2): string => JSON.stringify(JSON.parse(text), null, indent) + '\n';
