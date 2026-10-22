import { unsafeCSSVarV2 } from '@blocksuite/cloud-notes-shared/theme';
import { css } from 'lit';

import {
  ADD_BLOCK_WIDGET_WIDTH,
  DRAG_HANDLE_CONTAINER_WIDTH,
} from './config.js';

export const styles = css`
  .cloud-notes-drag-handle-widget {
    display: flex;
    position: absolute;
    left: 0;
    top: 0;
    contain: size layout;
    pointer-events: none;
  }

  .cloud-notes-add-block-widget-container {
    top: 0;
    left: 0;
    position: absolute;
    display: flex;
    justify-content: center;
    width: ${ADD_BLOCK_WIDGET_WIDTH}px;
    min-height: 12px;
    pointer-events: none;
    user-select: none;
    box-sizing: border-box;
  }

  .cloud-notes-drag-handle-container {
    top: 0;
    left: 0;
    position: absolute;
    display: flex;
    justify-content: center;
    width: ${DRAG_HANDLE_CONTAINER_WIDTH}px;
    min-height: 12px;
    pointer-events: auto;
    user-select: none;
    box-sizing: border-box;
  }
  .cloud-notes-drag-handle-container:hover {
    cursor: grab;
  }

  .cloud-notes-drag-handle-grabber {
    width: 4px;
    height: 100%;
    border-radius: 1px;
    background: var(--cloud-notes-placeholder-color);
    transition: width 0.25s ease;
  }

  .cloud-notes-drag-handle-grabber.dots {
    width: 14px;
    height: 26px;
    box-sizing: border-box;
    padding: 5px 2px;
    border-radius: 4px;
    gap: 2px;
    display: flex;
    flex-wrap: wrap;
    background-color: transparent;
    transform: translateX(-100%);
    transition: unset;
  }

  .cloud-notes-drag-handle-grabber.dots:hover {
    background-color: ${unsafeCSSVarV2('layer/background/hoverOverlay')};
  }

  .cloud-notes-drag-handle-grabber.dots > .dot {
    width: 4px;
    height: 4px;
    border-radius: 50%;
    flex: 0 0 4px;
    background-color: ${unsafeCSSVarV2('icon/secondary')};
  }

  @media print {
    .cloud-notes-drag-handle-widget {
      display: none;
    }
  }
  .cloud-notes-drag-hover-rect {
    position: absolute;
    top: 0;
    left: 0;
    border-radius: 6px;
    background: var(--cloud-notes-hover-color);
    pointer-events: none;
    z-index: 2;
    animation: expand 0.25s forwards;
  }
  @keyframes expand {
    0% {
      width: 0;
      height: 0;
    }
  }
`;
