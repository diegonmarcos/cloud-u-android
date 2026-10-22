import { css } from 'lit';

export const embedNoteContentStyles = css`
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-divider,
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-divider > * {
    margin-top: 0px !important;
    margin-bottom: 0px !important;
    padding-top: 8px;
    padding-bottom: 8px;
  }
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph,
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-list {
    margin-top: 4px !important;
    margin-bottom: 4px !important;
    padding: 0 2px;
  }
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph *,
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-list * {
    margin-top: 0px !important;
    margin-bottom: 0px !important;
    padding-top: 0;
    padding-bottom: 0;
    line-height: 20px;
    font-size: var(--cloud-notes-font-xs);
    font-weight: 400;
  }
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-list .cloud-notes-list-block__prefix {
    height: 20px;
  }
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph .quote {
    padding-left: 15px;
    padding-top: 8px;
    padding-bottom: 8px;
  }
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph:has(.h1),
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph:has(.h2),
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph:has(.h3),
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph:has(.h4),
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph:has(.h5),
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph:has(.h6) {
    margin-top: 6px !important;
    margin-bottom: 4px !important;
    padding: 0 2px;
  }
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph:has(.h1) *,
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph:has(.h2) *,
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph:has(.h3) *,
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph:has(.h4) *,
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph:has(.h5) *,
  .cloud-notes-embed-doc-content-note-blocks cloud-notes-paragraph:has(.h6) * {
    margin-top: 0px !important;
    margin-bottom: 0px !important;
    padding-top: 0;
    padding-bottom: 0;
    line-height: 20px;
    font-size: var(--cloud-notes-font-xs);
    font-weight: 600;
  }

  .cloud-notes-embed-doc-content-note-blocks inline-comment {
    background-color: unset !important;
    border-bottom: unset !important;
  }

  .cloud-notes-embed-linked-doc-block.horizontal {
    cloud-notes-paragraph,
    cloud-notes-list {
      margin-top: 0 !important;
      margin-bottom: 0 !important;
      max-height: 40px;
      overflow: hidden;
      display: flex;
    }
    cloud-notes-paragraph .quote {
      padding-top: 4px;
      padding-bottom: 4px;
      height: 28px;
    }
    cloud-notes-paragraph .quote::after {
      height: 20px;
      margin-top: 4px !important;
      margin-bottom: 4px !important;
    }
  }
`;
