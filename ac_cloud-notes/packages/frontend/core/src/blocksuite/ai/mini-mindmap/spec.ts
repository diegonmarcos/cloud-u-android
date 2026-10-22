import { SurfaceBlockSchema } from '@blocksuite/cloud-notes/blocks/surface';
import { ConnectorElementRendererExtension } from '@blocksuite/cloud-notes/gfx/connector';
import {
  MindmapElementRendererExtension,
  MindMapView,
} from '@blocksuite/cloud-notes/gfx/mindmap';
import { ShapeElementRendererExtension } from '@blocksuite/cloud-notes/gfx/shape';
import { TextElementRendererExtension } from '@blocksuite/cloud-notes/gfx/text';
import { RootBlockSchema } from '@blocksuite/cloud-notes/model';
import {
  DocModeService,
  ThemeService,
} from '@blocksuite/cloud-notes/shared/services';
import { BlockViewExtension, FlavourExtension } from '@blocksuite/cloud-notes/std';
import { ToolController } from '@blocksuite/cloud-notes/std/gfx';
import type { BlockSchema, ExtensionType } from '@blocksuite/cloud-notes/store';
import { literal } from 'lit/static-html.js';
import type { z } from 'zod';

import { MindmapService } from './mindmap-service.js';
import { MindmapSurfaceBlockService } from './surface-service.js';

export const MiniMindmapSpecs: ExtensionType[] = [
  DocModeService,
  ThemeService,
  FlavourExtension('cloud-notes:page'),
  MindmapService,
  ToolController,
  BlockViewExtension('cloud-notes:page', literal`mini-mindmap-root-block`),
  FlavourExtension('cloud-notes:surface'),
  MindMapView,
  MindmapSurfaceBlockService,
  BlockViewExtension('cloud-notes:surface', literal`mini-mindmap-surface-block`),
  TextElementRendererExtension,
  MindmapElementRendererExtension,
  ShapeElementRendererExtension,
  ConnectorElementRendererExtension,
];

export const MiniMindmapSchema: z.infer<typeof BlockSchema>[] = [
  RootBlockSchema,
  SurfaceBlockSchema,
];
