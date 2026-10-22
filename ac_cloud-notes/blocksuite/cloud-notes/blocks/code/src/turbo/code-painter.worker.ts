import type {
  BlockLayout,
  BlockLayoutPainter,
  WorkerToHostMessage,
} from '@blocksuite/cloud-notes-gfx-turbo-renderer';
import { BlockLayoutPainterExtension } from '@blocksuite/cloud-notes-gfx-turbo-renderer/painter';

export interface CodeLayout extends BlockLayout {
  type: 'cloud-notes:code';
}

function isCodeLayout(layout: BlockLayout): layout is CodeLayout {
  return layout.type === 'cloud-notes:code';
}

class CodeLayoutPainter implements BlockLayoutPainter {
  paint(
    ctx: OffscreenCanvasRenderingContext2D,
    layout: BlockLayout,
    layoutBaseX: number,
    layoutBaseY: number
  ): void {
    if (!isCodeLayout(layout)) {
      const message: WorkerToHostMessage = {
        type: 'paintError',
        error: 'Invalid layout format',
        blockType: 'cloud-notes:code',
      };
      self.postMessage(message);
      return;
    }

    // Get the layout dimensions
    const x = layout.rect.x - layoutBaseX;
    const y = layout.rect.y - layoutBaseY;
    const width = layout.rect.w;
    const height = layout.rect.h;

    // Simple white rectangle for now
    ctx.fillStyle = 'white';
    ctx.fillRect(x, y, width, height);

    // Add a border to visualize the code block
    ctx.strokeStyle = 'rgba(0, 0, 0, 0.1)';
    ctx.lineWidth = 1;
    ctx.strokeRect(x, y, width, height);
  }
}

export const CodeLayoutPainterExtension = BlockLayoutPainterExtension(
  'cloud-notes:code',
  CodeLayoutPainter
);
