import { cssVarV2 } from '@toeverything/theme/v2';
import { unsafeCSS } from 'lit';

export const dataViewCssVariable = () => {
  return `
  --data-view-cell-text-size:14px;
  --data-view-cell-text-line-height:22px;
`;
};
export const dataViewCommonStyle = (selector: string) => `
  ${selector}{
    ${dataViewCssVariable()}
  }
  .with-data-view-css-variable{
    ${dataViewCssVariable()}
    font-family: var(--cloud-notes-font-family)
  }
  .dv-pd-2{
    padding:2px;
  }
  .dv-pd-4{
    padding:4px;
  }
  .dv-pd-8{
    padding:8px;
  }
  .dv-hover:hover, .dv-hover.active{
    background-color: var(--cloud-notes-hover-color);
    cursor: pointer;
  }
  .dv-icon-16{
    font-size: 16px;
  }
  .dv-icon-16 svg{
    width: 16px;
    height: 16px;
    color: var(--cloud-notes-icon-color);
    fill: var(--cloud-notes-icon-color);
  }
  .dv-icon-20 svg{
    width: 20px;
    height: 20px;
    color: var(--cloud-notes-icon-color);
    fill: var(--cloud-notes-icon-color);
  }
  .dv-border{
    border: 1px solid ${unsafeCSS(cssVarV2.layer.insideBorder.border)};
  }
  .dv-round-4{
    border-radius: 4px;
  }
  .dv-round-8{
    border-radius: 8px;
  }
  .dv-color-2{
    color: var(--cloud-notes-text-secondary-color);
  }
  .dv-shadow-2{
    box-shadow: var(--cloud-notes-shadow-2)
  }
  .dv-divider-h{
    height: 1px;
    background-color: var(--cloud-notes-divider-color);
    margin: 8px 0;
  }
`;
