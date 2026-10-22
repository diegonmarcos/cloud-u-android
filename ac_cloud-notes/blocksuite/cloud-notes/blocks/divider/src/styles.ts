import { css } from 'lit';

export const dividerBlockStyles = css`
  .cloud-notes-divider-block-container {
    position: relative;
    width: 100%;
    height: 1px;
    display: flex;
    flex-direction: column;
    justify-content: center;
    padding: 18px 8px;
    margin-top: var(--cloud-notes-paragraph-space);
  }
  .cloud-notes-divider-block-container hr {
    border: none;
    border-top: 1px solid var(--cloud-notes-divider-color);
    width: 100%;
  }
`;
