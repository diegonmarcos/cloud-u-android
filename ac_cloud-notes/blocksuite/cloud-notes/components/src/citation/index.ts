import { CitationCard } from './citation';

export * from './citation';

export function effects() {
  customElements.define('cloud-notes-citation-card', CitationCard);
}
