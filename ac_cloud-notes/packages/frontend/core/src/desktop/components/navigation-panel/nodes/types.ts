import type { DropTargetDropEvent, DropTargetOptions } from '@cloud-notes/component';
import type { CloudNotesDNDData } from '@cloud-notes/core/types/dnd';

import type { NavigationPanelTreeNodeDropEffect } from '../tree';
import type { NodeOperation } from '../tree/types';

/**
 * The interface for a generic navigation panel node.
 *
 * # Drop controlled area
 *
 * When an element is dragged over the node, there are two controlled areas depending on the mouse position.
 *
 * **Make Child Area**:
 * When the mouse is in the center area of the node, it is in `Make Child Area`,
 * `canDrop`, `onDrop`, and `dropEffect` are handled by the node itself.
 *
 * **Edge Area**:
 * When the mouse is at the upper edge, lower edge, or front of a node, it is located in the `Edge Area`,
 * and all drop events are handled by the node's parent, which callbacks in this interface.
 *
 * The controlled area can be distinguished by `data.treeInstruction.type` in the callback parameter.
 */
export interface GenericNavigationPanelNode {
  /**
   * Tell the node and dropTarget where the node is located in the tree
   */
  location?: CloudNotesDNDData['draggable']['from'];
  /**
   * Whether the node is allowed to reorder with its sibling nodes
   */
  reorderable?: boolean;
  /**
   * Additional operations to be displayed in the node
   */
  operations?: NodeOperation[];
  /**
   * Control whether drop is allowed, the callback will be called when dragging.
   */
  canDrop?: DropTargetOptions<CloudNotesDNDData>['canDrop'];
  /**
   * Called when an element is dropped over the node.
   */
  onDrop?: (data: DropTargetDropEvent<CloudNotesDNDData>) => void;
  /**
   * The drop effect to be used when an element is dropped over the node.
   */
  dropEffect?: NavigationPanelTreeNodeDropEffect;
  /**
   * The path segments to the parent node in the navigation tree.
   * Used to persist the node's collapsed/expanded state in cache storage.
   */
  parentPath: string[];
}
