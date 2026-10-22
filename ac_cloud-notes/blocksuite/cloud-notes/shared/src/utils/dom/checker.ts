import type { EditorHost } from '@blocksuite/std';

export function isInsidePageEditor(host?: EditorHost) {
  if (!host) return false;
  return Array.from(host.children).some(
    v => v.tagName.toLowerCase() === 'cloud-notes-page-root'
  );
}

export function isInsideEdgelessEditor(host?: EditorHost) {
  if (!host) return false;

  return Array.from(host.children).some(
    v =>
      v.tagName.toLowerCase() === 'cloud-notes-edgeless-root' ||
      v.tagName.toLowerCase() === 'cloud-notes-edgeless-root-preview'
  );
}
