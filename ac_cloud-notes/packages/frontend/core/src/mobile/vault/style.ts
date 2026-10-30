// One scoped stylesheet for the whole vault view (class prefix cnv-). Colours follow the
// app theme through currentColor and translucent greys, so light and dark both work.
export const VAULT_CSS = `
.cnv-root{display:flex;flex-direction:column;height:100%;min-height:0;font-size:14px;position:relative;overflow:hidden}
.cnv-body{display:flex;flex:1;min-height:0;position:relative}
.cnv-ribbon{display:flex;flex-direction:column;gap:2px;padding:6px 4px;border-right:1px solid rgba(128,128,128,.25);flex:none}
.cnv-ribbon.cnv-narrow{flex-direction:row;overflow-x:auto;border-right:0;border-bottom:1px solid rgba(128,128,128,.25)}
.cnv-btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-width:36px;min-height:36px;padding:4px 8px;border:0;border-radius:6px;background:transparent;color:inherit;font:inherit;cursor:pointer}
.cnv-btn:hover,.cnv-btn[aria-pressed=true]{background:rgba(128,128,128,.22)}
.cnv-btn.cnv-primary{background:rgba(122,140,255,.35)}
.cnv-side{width:264px;flex:none;display:flex;flex-direction:column;min-height:0;border-right:1px solid rgba(128,128,128,.25);background:rgba(128,128,128,.05)}
.cnv-side.cnv-right{border-right:0;border-left:1px solid rgba(128,128,128,.25)}
.cnv-side.cnv-drawer{position:absolute;top:0;bottom:0;z-index:20;width:min(86vw,320px);background:var(--cloud-notes-background-primary-color,#1f2023);box-shadow:0 0 24px rgba(0,0,0,.45)}
.cnv-side.cnv-drawer.cnv-right{right:0}
.cnv-tabbar{display:flex;border-bottom:1px solid rgba(128,128,128,.25);flex:none;overflow-x:auto}
.cnv-tab{padding:8px 12px;border:0;border-bottom:2px solid transparent;background:transparent;color:inherit;font:inherit;opacity:.7;cursor:pointer;white-space:nowrap}
.cnv-tab[aria-selected=true]{opacity:1;border-bottom-color:rgb(122,140,255)}
.cnv-scroll{flex:1;min-height:0;overflow:auto;padding:8px}
.cnv-row{display:flex;align-items:center;gap:6px;width:100%;padding:6px 8px;border:0;border-radius:6px;background:transparent;color:inherit;font:inherit;text-align:left;cursor:pointer}
.cnv-row:hover{background:rgba(128,128,128,.18)}
.cnv-row[aria-current=true]{background:rgba(122,140,255,.28)}
.cnv-dim{opacity:.65;font-size:12px}
.cnv-main{flex:1;min-width:0;display:flex;flex-direction:column;min-height:0}
.cnv-pane{flex:1;min-height:0;overflow:auto;padding:12px 16px}
.cnv-input{width:100%;box-sizing:border-box;padding:8px 10px;border:1px solid rgba(128,128,128,.4);border-radius:6px;background:rgba(128,128,128,.1);color:inherit;font:inherit}
.cnv-editor{width:100%;height:100%;min-height:50vh;box-sizing:border-box;padding:12px;border:0;outline:0;resize:none;background:transparent;color:inherit;font:13px/1.5 ui-monospace,Menlo,Consolas,monospace;tab-size:2}
.cnv-status{display:flex;gap:14px;padding:4px 10px;border-top:1px solid rgba(128,128,128,.25);font-size:12px;opacity:.75;flex:none;overflow-x:auto;white-space:nowrap}
.cnv-note{max-width:46rem;margin:0 auto;line-height:1.6;overflow-wrap:anywhere}
.cnv-note h1,.cnv-note h2,.cnv-note h3{line-height:1.25}
.cnv-note pre{overflow:auto;padding:10px;border-radius:6px;background:rgba(128,128,128,.16)}
.cnv-note code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.9em}
.cnv-note table{border-collapse:collapse}.cnv-note td,.cnv-note th{border:1px solid rgba(128,128,128,.4);padding:4px 8px}
.cnv-note blockquote{margin:8px 0;padding:2px 12px;border-left:3px solid rgba(128,128,128,.5)}
.cnv-note a.internal-link{color:rgb(122,140,255);text-decoration:none;border-bottom:1px solid rgba(122,140,255,.5)}
.cnv-note a.tag{color:rgb(242,166,90);text-decoration:none}
.cnv-note .callout{margin:10px 0;padding:8px 12px;border-radius:6px;border-left:4px solid rgb(68,138,255);background:rgba(68,138,255,.12)}
.cnv-note .callout[data-callout=warning],.cnv-note .callout[data-callout=caution],.cnv-note .callout[data-callout=attention]{border-color:#e9973f;background:rgba(233,151,63,.14)}
.cnv-note .callout[data-callout=danger],.cnv-note .callout[data-callout=error],.cnv-note .callout[data-callout=failure],.cnv-note .callout[data-callout=bug]{border-color:#fb464c;background:rgba(251,70,76,.14)}
.cnv-note .callout[data-callout=tip],.cnv-note .callout[data-callout=success],.cnv-note .callout[data-callout=check],.cnv-note .callout[data-callout=done]{border-color:#44cf6e;background:rgba(68,207,110,.14)}
.cnv-note .callout-title{font-weight:600;margin-bottom:4px}
.cnv-note .embed{display:block;margin:8px 0;padding:6px 10px;border:1px solid rgba(128,128,128,.4);border-radius:6px}
.cnv-note img{max-width:100%}
.cnv-note .task-list-item{list-style:none;margin-left:-1.2em}
.cnv-props{margin:0 auto 12px;max-width:46rem;padding:8px 12px;border:1px solid rgba(128,128,128,.3);border-radius:6px;font-size:13px}
.cnv-props dt{opacity:.65;float:left;clear:left;margin-right:8px}.cnv-props dd{margin:0 0 2px}
.cnv-mermaid svg{max-width:100%;height:auto}
.cnv-banner{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:8px 12px;border-bottom:1px solid rgba(128,128,128,.25);font-size:13px;background:rgba(233,151,63,.1)}
.cnv-iframe{flex:1;width:100%;border:0;background:#fff;min-height:60vh}
.cnv-tree-row{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px;display:flex;gap:6px;padding:2px 0;cursor:default;white-space:nowrap}
.cnv-board{position:relative;flex:1;min-height:0;overflow:hidden;touch-action:none;background-image:radial-gradient(rgba(128,128,128,.35) 1px,transparent 1px);background-size:24px 24px}
.cnv-node{position:absolute;box-sizing:border-box;padding:8px;border:2px solid rgba(128,128,128,.55);border-radius:8px;background:var(--cloud-notes-background-primary-color,#2a2c30);color:inherit;overflow:hidden;font-size:13px;user-select:none}
.cnv-node.cnv-group{background:rgba(128,128,128,.07);border-style:dashed;padding:4px 8px}
.cnv-node[aria-selected=true]{outline:2px solid rgb(122,140,255)}
.cnv-board-tools{position:absolute;left:8px;right:8px;bottom:96px;display:flex;gap:6px;flex-wrap:wrap;justify-content:center;z-index:5}
.cnv-board-tools .cnv-btn{background:var(--cloud-notes-background-primary-color,#2a2c30);border:1px solid rgba(128,128,128,.4)}
.cnv-palette{position:absolute;inset:0;z-index:40;display:flex;align-items:flex-start;justify-content:center;padding-top:8vh;background:rgba(0,0,0,.4)}
.cnv-palette-box{width:min(94vw,560px);max-height:70vh;display:flex;flex-direction:column;border-radius:10px;background:var(--cloud-notes-background-primary-color,#1f2023);box-shadow:0 12px 40px rgba(0,0,0,.5);overflow:hidden}
.cnv-start{max-width:34rem;margin:0 auto;padding:20px 16px 110px}
`;
