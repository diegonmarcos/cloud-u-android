import { css } from 'lit';

export const styles = css`
  .cloud-notes-block-component.border.light .selected-style {
    border-radius: 8px;
    box-shadow: 0px 0px 0px 1px var(--affine-brand-color);
  }
  .cloud-notes-block-component.border.dark .selected-style {
    border-radius: 8px;
    box-shadow: 0px 0px 0px 1px var(--affine-brand-color);
  }
  @media print {
    .cloud-notes-block-component.border.light .selected-style,
    .cloud-notes-block-component.border.dark .selected-style {
      box-shadow: none;
    }
  }
`;
