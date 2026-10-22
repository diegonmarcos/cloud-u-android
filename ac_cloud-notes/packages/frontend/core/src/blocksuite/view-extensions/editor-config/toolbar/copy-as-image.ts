import { notify } from '@cloud-notes/component';
import { isMindmapChild, isMindMapRoot } from '@cloud-notes/core/blocksuite/ai';
import { EditorService } from '@cloud-notes/core/modules/editor';
import { apis } from '@cloud-notes/electron-api';
import { I18n } from '@cloud-notes/i18n';
import type { MenuContext } from '@blocksuite/cloud-notes/components/toolbar';
import { Bound, getCommonBound } from '@blocksuite/cloud-notes/global/gfx';
import type { BlockStdScope } from '@blocksuite/cloud-notes/std';
import {
  type GfxBlockElementModel,
  GfxControllerIdentifier,
  type GfxModel,
  GfxPrimitiveElementModel,
  isGfxGroupCompatibleModel,
} from '@blocksuite/cloud-notes/std/gfx';
import { CopyAsImgaeIcon } from '@blocksuite/icons/lit';
import type { FrameworkProvider } from '@toeverything/infra';

const snapshotStyle = `
  cloud-notes-edgeless-root .widgets-container,
  .copy-as-image-transparent {
    opacity: 0;
  }
  .edgeless-background {
    background-image: none;
  }
`;

function getSelectedRect() {
  const selected = document
    .querySelector('edgeless-selected-rect')
    ?.shadowRoot?.querySelector('.cloud-notes-edgeless-selected-rect');
  if (!selected) {
    throw new Error('Missing edgeless selected rect');
  }
  return selected.getBoundingClientRect();
}

function expandBound(bound: Bound, margin: number) {
  const x = bound.x - margin;
  const y = bound.y - margin;
  const w = bound.w + margin * 2;
  const h = bound.h + margin * 2;
  return new Bound(x, y, w, h);
}

function isOverlap(target: Bound, source: Bound) {
  const { x, y, w, h } = source;
  const left = target.x;
  const top = target.y;
  const right = target.x + target.w;
  const bottom = target.y + target.h;

  return x < right && y < bottom && x + w > left && y + h > top;
}

function isInside(target: Bound, source: Bound) {
  const { x, y, w, h } = source;
  const left = target.x;
  const top = target.y;
  const right = target.x + target.w;
  const bottom = target.y + target.h;

  return x >= left && y >= top && x + w <= right && y + h <= bottom;
}

function hideEdgelessElements(elements: GfxModel[], std: BlockStdScope) {
  elements.forEach(ele => {
    if (ele instanceof GfxPrimitiveElementModel) {
      (ele as any).lastOpacity = ele.opacity;
      ele.opacity = 0;
    } else {
      const block = std.view.getBlock(ele.id);
      if (!block) return;
      block.classList.add('copy-as-image-transparent');
    }
  });
}

function showEdgelessElements(elements: GfxModel[], std: BlockStdScope) {
  elements.forEach(ele => {
    if (ele instanceof GfxPrimitiveElementModel) {
      ele.opacity = (ele as any).lastOpacity;
      delete (ele as any).lastOpacity;
    } else {
      const block = std.view.getBlock(ele.id);
      if (!block) return;
      block.classList.remove('copy-as-image-transparent');
    }
  });
}

function withDescendantElements(elements: GfxModel[]) {
  const set = new Set<GfxModel>();
  elements.forEach(element => {
    if (set.has(element)) return;
    set.add(element);
    if (isGfxGroupCompatibleModel(element)) {
      element.descendantElements.forEach(descendant => set.add(descendant));
    }
  });
  return [...set];
}

const MARGIN = 20;

export function copyAsImage(std: BlockStdScope) {
  if (!apis) {
    notify.error({
      title: I18n.t('com.cloud-notes.copy.asImage.notAvailable.title'),
      message: I18n.t('com.cloud-notes.copy.asImage.notAvailable.message'),
      actions: [
        {
          key: 'download',
          label: I18n.t('com.cloud-notes.copy.asImage.notAvailable.action'),
          onClick: () => {
            window.open('https://notes.diegonmarcos.com/download');
          },
        },
      ],
    });
    return;
  }

  const gfx = std.get(GfxControllerIdentifier);

  let selected = gfx.selection.selectedElements;
  // select mindmap if root node selected
  const maybeMindmap = selected[0];
  const mindmapId = maybeMindmap.group?.id;
  if (
    selected.length === 1 &&
    mindmapId &&
    (isMindMapRoot(maybeMindmap) || isMindmapChild(maybeMindmap))
  ) {
    gfx.selection.set({ elements: [mindmapId] });
  }

  // select bound
  selected = gfx.selection.selectedElements;
  const elements = withDescendantElements(selected);
  const bounds = elements.map(element => Bound.deserialize(element.xywh));
  const bound = getCommonBound(bounds);
  if (!bound) return;
  const { zoom } = gfx.viewport;
  const exBound = expandBound(bound, MARGIN * zoom);

  // fit to screen
  if (
    !isInside(gfx.viewport.viewportBounds, exBound) ||
    gfx.viewport.zoom < 1
  ) {
    gfx.viewport.setViewportByBound(bound, [20, 20, 20, 20], false);
    if (gfx.viewport.zoom > 1) {
      gfx.viewport.setZoom(1);
    }
  }

  // hide unselected overlap elements
  const overlapElements = gfx.gfxElements.filter(ele => {
    const eleBound = Bound.deserialize(ele.xywh);
    const exEleBound = expandBound(eleBound, MARGIN * zoom);
    const isSelected = elements.includes(ele);
    return !isSelected && isOverlap(exBound, exEleBound);
  });
  hideEdgelessElements(overlapElements, std);

  // add css style
  const styleEle = document.createElement('style');
  styleEle.innerHTML = snapshotStyle;
  document.head.append(styleEle);

  // capture image
  setTimeout(() => {
    if (!apis) return;
    try {
      const domRect = getSelectedRect();
      const { zoom } = gfx.viewport;
      const isFrameSelected =
        selected.length === 1 &&
        (selected[0] as GfxBlockElementModel).flavour === 'affine:frame';
      const margin = isFrameSelected ? -2 : MARGIN * zoom;

      gfx.selection.clear();

      apis.ui
        .captureArea({
          x: domRect.left - margin,
          y: domRect.top - margin,
          width: domRect.width + margin * 2,
          height: domRect.height + margin * 2,
        })
        .then(() => {
          notify.success({
            title: I18n.t('com.cloud-notes.copy.asImage.success'),
          });
        })
        .catch(e => {
          notify.error({
            title: I18n.t('com.cloud-notes.copy.asImage.failed'),
            message: String(e),
          });
        })
        .finally(() => {
          styleEle.remove();
          showEdgelessElements(overlapElements, std);
        });
    } catch (e) {
      styleEle.remove();
      showEdgelessElements(overlapElements, std);
      notify.error({
        title: I18n.t('com.cloud-notes.copy.asImage.failed'),
        message: String(e),
      });
    }
  }, 100);
}

export function createCopyAsPngMenuItem(framework: FrameworkProvider) {
  return {
    icon: CopyAsImgaeIcon({ width: '20', height: '20' }),
    label: 'Copy as Image',
    type: 'copy-as-image',
    when: (ctx: MenuContext) => {
      if (ctx.isEmpty()) return false;
      const { editor } = framework.get(EditorService);
      const mode = editor.mode$.value;
      return mode === 'edgeless';
    },
    action: (ctx: MenuContext) => {
      const std = ctx.std;
      return copyAsImage(std);
    },
  };
}
