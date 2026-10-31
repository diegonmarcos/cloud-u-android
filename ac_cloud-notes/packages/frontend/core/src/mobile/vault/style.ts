// One scoped stylesheet for the whole vault view (class prefix cnv-). #1369: every colour, size and radius is an
// Obsidian default-theme variable (component/src/theme/obsidian.gen.css, generated from data/obsidian-theme.json),
// so the vault wears the app theme in light and dark. No literal colours here (test/test-obsidian-theme.sh).
export const VAULT_CSS = `
.cnv-root{display:flex;flex-direction:column;height:100%;min-height:0;font-size:var(--font-ui-medium);font-family:var(--font-interface);color:var(--text-normal);background:var(--background-primary);position:relative;overflow:hidden}
.cnv-body{display:flex;flex:1;min-height:0;position:relative}
.cnv-ribbon{display:flex;flex-direction:column;gap:2px;padding:6px 4px;border-right:1px solid var(--background-modifier-border);background:var(--background-secondary);flex:none}
.cnv-ribbon.cnv-narrow{flex-direction:row;overflow-x:auto;border-right:0;border-bottom:1px solid var(--background-modifier-border)}
.cnv-btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-width:36px;min-height:36px;padding:4px 8px;border:0;border-radius:var(--radius-s);background:transparent;color:var(--text-muted);font:inherit;cursor:pointer}
.cnv-btn:hover,.cnv-btn[aria-pressed=true]{background:var(--background-modifier-hover);color:var(--text-normal)}
.cnv-btn.cnv-primary{background:var(--interactive-accent);color:var(--text-on-accent)}
.cnv-side{width:264px;flex:none;display:flex;flex-direction:column;min-height:0;border-right:1px solid var(--background-modifier-border);background:var(--background-secondary)}
.cnv-side.cnv-right{border-right:0;border-left:1px solid var(--background-modifier-border)}
.cnv-side.cnv-drawer{position:absolute;top:0;bottom:0;z-index:20;width:min(86vw,320px);background:var(--background-secondary);box-shadow:0 0 24px var(--shadow)}
.cnv-side.cnv-drawer.cnv-right{right:0}
.cnv-scrim{position:absolute;inset:0;z-index:19;background:var(--background-modifier-cover)}
.cnv-drawer-actions{display:flex;flex-direction:column;padding:8px;border-bottom:1px solid var(--background-modifier-border);flex:none}
.cnv-drawer-actions .cnv-row{gap:12px;min-height:40px;font-size:var(--font-ui-medium);color:var(--text-normal)}
.cnv-drawer-actions .cnv-row svg{color:var(--text-muted)}
.cnv-side-tabs .cnv-tab{display:inline-flex;align-items:center;justify-content:center;min-width:36px}
.cnv-tabbar{display:flex;gap:2px;padding:4px 4px 0;background:var(--background-secondary);border-bottom:1px solid var(--background-modifier-border);flex:none;overflow-x:auto}
.cnv-tab{padding:6px 12px;border:0;border-radius:var(--radius-m) var(--radius-m) 0 0;background:transparent;color:var(--text-muted);font-size:var(--font-ui-small);cursor:pointer;white-space:nowrap}
.cnv-tab[aria-selected=true]{color:var(--text-normal);background:var(--background-primary)}
.cnv-scroll{flex:1;min-height:0;overflow:auto;padding:8px}
.cnv-row{display:flex;align-items:center;gap:6px;width:100%;padding:6px 8px;border:0;border-radius:var(--radius-s);background:transparent;color:var(--text-normal);font-size:var(--font-ui-small);text-align:left;cursor:pointer}
.cnv-row:hover{background:var(--background-modifier-hover)}
.cnv-row[aria-current=true]{background:var(--background-modifier-active-hover);color:var(--text-normal)}
.cnv-dim{color:var(--text-faint);font-size:var(--font-ui-smaller)}
.cnv-main{flex:1;min-width:0;display:flex;flex-direction:column;min-height:0}
.cnv-pane{flex:1;min-height:0;overflow:auto;padding:12px 16px}
.cnv-input{width:100%;box-sizing:border-box;padding:8px 10px;border:1px solid var(--background-modifier-border);border-radius:var(--radius-s);background:var(--background-primary);color:var(--text-normal);font:inherit}
.cnv-editor{width:100%;height:100%;min-height:50vh;box-sizing:border-box;padding:12px;border:0;outline:0;resize:none;background:transparent;color:inherit;font:var(--font-ui-small)/var(--line-height-normal) var(--font-monospace);tab-size:2}
.cnv-status{display:flex;gap:14px;padding:4px 10px;border-top:1px solid var(--background-modifier-border);background:var(--background-secondary);color:var(--text-muted);font-size:var(--font-ui-smaller);flex:none;overflow-x:auto;white-space:nowrap}
.cnv-note{max-width:700px;margin:0 auto;font-family:var(--font-text);font-size:var(--font-text-size);line-height:var(--line-height-normal);overflow-wrap:anywhere}
.cnv-note h1,.cnv-note h2,.cnv-note h3,.cnv-note h4{line-height:1.2;font-weight:700}.cnv-note h1{font-size:var(--h1-size)}.cnv-note h2{font-size:var(--h2-size)}.cnv-note h3{font-size:var(--h3-size)}.cnv-note h4{font-size:var(--h4-size)}
.cnv-note pre{overflow:auto;padding:10px;border-radius:var(--radius-s);background:var(--background-primary-alt);border:1px solid var(--background-modifier-border)}
.cnv-note code{font-family:var(--font-monospace);font-size:.875em}
.cnv-note table{border-collapse:collapse}.cnv-note td,.cnv-note th{border:1px solid var(--background-modifier-border);padding:4px 8px}
.cnv-note blockquote{margin:8px 0;padding:2px 12px;border-left:2px solid var(--blockquote-border-color)}
.cnv-note a,.cnv-note a.internal-link{color:var(--link-color);text-decoration:underline}
.cnv-note a.tag{color:var(--tag-color);background:var(--tag-background);border-radius:2em;padding:1px 8px;font-size:.875em;text-decoration:none}
.cnv-note .callout{margin:10px 0;padding:8px 12px;border-radius:var(--radius-s);border-left:4px solid var(--color-blue);background:var(--background-modifier-hover)}
.cnv-note .callout[data-callout=warning],.cnv-note .callout[data-callout=caution],.cnv-note .callout[data-callout=attention]{border-color:var(--color-orange);background:var(--background-tint-warning)}
.cnv-note .callout[data-callout=danger],.cnv-note .callout[data-callout=error],.cnv-note .callout[data-callout=failure],.cnv-note .callout[data-callout=bug]{border-color:var(--color-red);background:var(--background-tint-error)}
.cnv-note .callout[data-callout=tip],.cnv-note .callout[data-callout=success],.cnv-note .callout[data-callout=check],.cnv-note .callout[data-callout=done]{border-color:var(--color-green);background:var(--background-tint-success)}
.cnv-note .callout-title{font-weight:600;margin-bottom:4px}
.cnv-note .embed{display:block;margin:8px 0;padding:6px 10px;border:1px solid var(--background-modifier-border);border-radius:var(--radius-s)}
.cnv-note img{max-width:100%}
.cnv-note .task-list-item{list-style:none;margin-left:-1.2em}
.cnv-props{margin:0 auto 12px;max-width:700px;padding:8px 12px;border:1px solid var(--background-modifier-border);border-radius:var(--radius-m);font-size:var(--font-ui-small)}
.cnv-props dt{color:var(--text-muted);float:left;clear:left;margin-right:8px}.cnv-props dd{margin:0 0 2px}
.cnv-mermaid svg{max-width:100%;height:auto}
.cnv-banner{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:8px 12px;border-bottom:1px solid var(--background-modifier-border);font-size:var(--font-ui-small);background:var(--background-tint-warning)}
.cnv-iframe{flex:1;width:100%;border:0;background:var(--color-base-00);min-height:60vh}
.cnv-tree-row{font-family:var(--font-monospace);font-size:var(--font-ui-small);display:flex;gap:6px;padding:2px 0;cursor:default;white-space:nowrap}
.cnv-board{position:relative;flex:1;min-height:0;overflow:hidden;touch-action:none;background-color:var(--background-primary);background-image:radial-gradient(var(--background-modifier-border-hover) 1px,transparent 1px);background-size:24px 24px}
.cnv-node{position:absolute;box-sizing:border-box;padding:8px;border:2px solid var(--background-modifier-border-focus);border-radius:var(--radius-m);background:var(--background-primary);color:var(--text-normal);overflow:hidden;font-size:var(--font-ui-small);user-select:none}
.cnv-node.cnv-group{background:var(--background-primary-alt);border-style:dashed;padding:4px 8px}
.cnv-node[aria-selected=true]{outline:2px solid var(--interactive-accent)}
.cnv-board-tools{position:absolute;left:8px;right:8px;bottom:96px;display:flex;gap:6px;flex-wrap:wrap;justify-content:center;z-index:5}
.cnv-board-tools .cnv-btn{background:var(--background-primary);border:1px solid var(--background-modifier-border)}
.cnv-palette{position:absolute;inset:0;z-index:40;display:flex;align-items:flex-start;justify-content:center;padding-top:10vh;background:var(--background-modifier-cover)}
.cnv-palette-box{width:min(94vw,700px);max-height:70vh;display:flex;flex-direction:column;border-radius:var(--radius-l);border:1px solid var(--background-modifier-border);background:var(--background-primary);box-shadow:0 12px 40px var(--shadow);overflow:hidden}
.cnv-palette-box .cnv-input{border:0;border-bottom:1px solid var(--background-modifier-border);border-radius:0;padding:12px 16px;font-size:var(--font-ui-medium)}
.cnv-palette-box .cnv-row{padding:8px 16px;font-size:var(--font-ui-medium)}
.cnv-palette-box .cnv-row[aria-current=true],.cnv-palette-box .cnv-row:hover{background:var(--background-modifier-hover)}
.cnv-palette-foot{display:flex;gap:16px;padding:8px 16px;border-top:1px solid var(--background-modifier-border);color:var(--text-faint);font-size:var(--font-ui-smaller)}
.cnv-start{max-width:34rem;margin:0 auto;padding:20px 16px 110px}
`;
