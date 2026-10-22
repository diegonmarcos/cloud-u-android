import { css } from 'lit';

export const listPrefix = css`
  .cloud-notes-list-block__prefix {
    display: flex;
    color: var(--cloud-notes-blue-700);
    font-size: var(--cloud-notes-font-sm);
    user-select: none;
    position: relative;
  }

  .cloud-notes-list-block__numbered {
    min-width: 22px;
    height: 24px;
    margin-left: 2px;
  }

  .cloud-notes-list-block__todo-prefix {
    display: flex;
    align-items: center;
    cursor: pointer;
    width: 24px;
    height: 24px;
    color: var(--cloud-notes-icon-color);
  }

  .cloud-notes-list-block__todo-prefix.readonly {
    cursor: default;
  }

  .cloud-notes-list-block__todo-prefix > svg {
    width: 20px;
    height: 20px;
  }
`;

export const listBlockStyles = css`
  cloud-notes-list {
    display: block;
    font-size: var(--cloud-notes-font-base);
  }

  cloud-notes-list code {
    font-size: calc(var(--cloud-notes-font-base) - 3px);
    padding: 0px 4px 2px;
  }

  .cloud-notes-list-block-container {
    box-sizing: border-box;
    border-radius: 4px;
    position: relative;
  }
  .cloud-notes-list-block-container .cloud-notes-list-block-container {
    margin-top: 0;
  }
  .cloud-notes-list-rich-text-wrapper {
    position: relative;
    display: flex;
  }
  .cloud-notes-list-rich-text-wrapper rich-text {
    flex: 1;
  }

  .cloud-notes-list--checked {
    color: var(--cloud-notes-text-secondary-color);
  }

  ${listPrefix}
`;
