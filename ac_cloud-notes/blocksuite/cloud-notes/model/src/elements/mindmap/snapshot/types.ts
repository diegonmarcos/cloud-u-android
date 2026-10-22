export interface MindMapTreeNode {
  id: string;
  index: string;
  children: MindMapTreeNode[];
}

export interface MindMapNode {
  index: string;
  parent?: string;
}

export type MindMapJson = Record<string, MindMapNode>;

export interface MindMapElement {
  index: string;
  seed: number;
  children: {
    'cloud-notes:surface:ymap': boolean;
    json: MindMapJson;
  };
  layoutType: number;
  style: number;
  type: 'mindmap';
  id: string;
}
