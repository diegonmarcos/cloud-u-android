import {
  type ElementRenderer,
  ElementRendererExtension,
} from '@blocksuite/cloud-notes-block-surface';
import {
  DefaultTheme,
  type HighlighterElementModel,
} from '@blocksuite/cloud-notes-model';

export const highlighter: ElementRenderer<HighlighterElementModel> = (
  model,
  ctx,
  matrix,
  renderer
) => {
  const {
    rotate,
    deserializedXYWH: [, , w, h],
  } = model;
  const cx = w / 2;
  const cy = h / 2;

  ctx.setTransform(
    matrix.translateSelf(cx, cy).rotateSelf(rotate).translateSelf(-cx, -cy)
  );

  const color = renderer.getColorValue(
    model.color,
    DefaultTheme.hightlighterColor,
    true
  );

  ctx.fillStyle = color;

  ctx.fill(new Path2D(model.commands));
};

export const HighlighterElementRendererExtension = ElementRendererExtension(
  'highlighter',
  highlighter
);
