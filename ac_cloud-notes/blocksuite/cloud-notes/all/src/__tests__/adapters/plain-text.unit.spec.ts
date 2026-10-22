import { DefaultTheme, NoteDisplayMode } from '@blocksuite/cloud-notes-model';
import {
  embedSyncedDocMiddleware,
  PlainTextAdapter,
} from '@blocksuite/cloud-notes-shared/adapters';
import type {
  BlockSnapshot,
  DocSnapshot,
  TransformerMiddleware,
} from '@blocksuite/store';
import { describe, expect, test } from 'vitest';

import { createJob } from '../utils/create-job.js';
import { getProvider } from '../utils/get-provider.js';

const provider = getProvider();

describe('snapshot to plain text', () => {
  test('paragraph', async () => {
    const blockSnapshot: BlockSnapshot = {
      type: 'block',
      id: 'block:vu6SK6WJpW',
      flavour: 'cloud-notes:page',
      props: {
        title: {
          '$blocksuite:internal:text$': true,
          delta: [],
        },
      },
      children: [
        {
          type: 'block',
          id: 'block:Tk4gSPocAt',
          flavour: 'cloud-notes:surface',
          props: {
            elements: {},
          },
          children: [],
        },
        {
          type: 'block',
          id: 'block:WfnS5ZDCJT',
          flavour: 'cloud-notes:note',
          props: {
            xywh: '[0,0,800,95]',
            background: DefaultTheme.noteBackgrounColor,
            index: 'a0',
            hidden: false,
            displayMode: NoteDisplayMode.DocAndEdgeless,
          },
          children: [
            {
              type: 'block',
              id: 'block:Bdn8Yvqcny',
              flavour: 'cloud-notes:paragraph',
              props: {
                type: 'text',
                text: {
                  '$blocksuite:internal:text$': true,
                  delta: [
                    {
                      insert: 'aaa',
                    },
                    {
                      insert: 'bbb',
                      attributes: {
                        italic: true,
                      },
                    },
                    {
                      insert: 'ccc',
                      attributes: {
                        bold: true,
                      },
                    },
                  ],
                },
              },
              children: [
                {
                  type: 'block',
                  id: 'block:72SMa5mdLy',
                  flavour: 'cloud-notes:paragraph',
                  props: {
                    type: 'text',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'ddd',
                          attributes: {
                            italic: true,
                          },
                        },
                      ],
                    },
                  },
                  children: [],
                },
                {
                  type: 'block',
                  id: 'block:f-Z6nRrGK_',
                  flavour: 'cloud-notes:paragraph',
                  props: {
                    type: 'text',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'eee',
                          attributes: {
                            bold: true,
                          },
                        },
                      ],
                    },
                  },
                  children: [],
                },
                {
                  type: 'block',
                  id: 'block:I0Fmz5Nv02',
                  flavour: 'cloud-notes:paragraph',
                  props: {
                    type: 'text',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'fff',
                        },
                      ],
                    },
                  },
                  children: [],
                },
              ],
            },
            {
              type: 'block',
              id: 'block:12lDwMD7ec',
              flavour: 'cloud-notes:paragraph',
              props: {
                type: 'text',
                text: {
                  '$blocksuite:internal:text$': true,
                  delta: [
                    {
                      insert: 'ggg',
                    },
                  ],
                },
              },
              children: [],
            },
          ],
        },
      ],
    };

    const plainText = 'aaabbbccc\nddd\neee\nfff\nggg\n';
    const plainTextAdapter = new PlainTextAdapter(createJob(), provider);
    const target = await plainTextAdapter.fromBlockSnapshot({
      snapshot: blockSnapshot,
    });
    expect(target.file).toBe(plainText);
  });

  test('list', async () => {
    const blockSnapshot: BlockSnapshot = {
      type: 'block',
      id: 'block:m5hvdXHXS2',
      flavour: 'cloud-notes:page',
      version: 2,
      props: {
        title: {
          '$blocksuite:internal:text$': true,
          delta: [],
        },
      },
      children: [
        {
          type: 'block',
          id: 'block:Y4J-oO9h9d',
          flavour: 'cloud-notes:surface',
          version: 5,
          props: {
            elements: {},
          },
          children: [],
        },
        {
          type: 'block',
          id: 'block:1Ll22zT992',
          flavour: 'cloud-notes:note',
          version: 1,
          props: {
            xywh: '[0,0,800,95]',
            background: DefaultTheme.noteBackgrounColor,
            index: 'a0',
            hidden: false,
            displayMode: NoteDisplayMode.DocAndEdgeless,
            edgeless: {
              style: {
                borderRadius: 8,
                borderSize: 4,
                borderStyle: 'solid',
                shadowType: '--cloud-notes-note-shadow-box',
              },
            },
          },
          children: [
            {
              type: 'block',
              id: 'block:Fd0ZCYB7a4',
              flavour: 'cloud-notes:list',
              version: 1,
              props: {
                type: 'numbered',
                text: {
                  '$blocksuite:internal:text$': true,
                  delta: [
                    {
                      insert: 'aaa',
                    },
                  ],
                },
                checked: false,
                collapsed: false,
              },
              children: [
                {
                  type: 'block',
                  id: 'block:8-GeKDc06x',
                  flavour: 'cloud-notes:list',
                  version: 1,
                  props: {
                    type: 'numbered',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'bbb',
                        },
                      ],
                    },
                    checked: false,
                    collapsed: false,
                  },
                  children: [],
                },
                {
                  type: 'block',
                  id: 'block:f0c-9xKaEL',
                  flavour: 'cloud-notes:list',
                  version: 1,
                  props: {
                    type: 'bulleted',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'ccc',
                        },
                      ],
                    },
                    checked: false,
                    collapsed: false,
                  },
                  children: [],
                },
                {
                  type: 'block',
                  id: 'block:f0c-9xKaEL',
                  flavour: 'cloud-notes:list',
                  version: 1,
                  props: {
                    type: 'numbered',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'ddd',
                        },
                      ],
                    },
                    checked: false,
                    collapsed: false,
                  },
                  children: [],
                },
              ],
            },
            {
              type: 'block',
              id: 'block:Fd0ZCYB7a5',
              flavour: 'cloud-notes:list',
              version: 1,
              props: {
                type: 'numbered',
                text: {
                  '$blocksuite:internal:text$': true,
                  delta: [
                    {
                      insert: 'eee',
                    },
                  ],
                },
                checked: false,
                collapsed: false,
              },
              children: [],
            },
          ],
        },
      ],
    };

    const plainText = 'aaa\nbbb\nccc\nddd\neee\n';

    const plainTextAdapter = new PlainTextAdapter(createJob(), provider);
    const target = await plainTextAdapter.fromBlockSnapshot({
      snapshot: blockSnapshot,
    });
    expect(target.file).toEqual(plainText);
  });

  test('divider', async () => {
    const blockSnapshot: BlockSnapshot = {
      type: 'block',
      id: 'block:vu6SK6WJpW',
      flavour: 'cloud-notes:page',
      props: {
        title: {
          '$blocksuite:internal:text$': true,
          delta: [],
        },
      },
      children: [
        {
          type: 'block',
          id: 'block:Tk4gSPocAt',
          flavour: 'cloud-notes:surface',
          props: {
            elements: {},
          },
          children: [],
        },
        {
          type: 'block',
          id: 'block:WfnS5ZDCJT',
          flavour: 'cloud-notes:note',
          props: {
            xywh: '[0,0,800,95]',
            background: DefaultTheme.noteBackgrounColor,
            index: 'a0',
            hidden: false,
            displayMode: NoteDisplayMode.DocAndEdgeless,
          },
          children: [
            {
              type: 'block',
              id: 'block:Bdn8Yvqcny',
              flavour: 'cloud-notes:paragraph',
              props: {
                type: 'text',
                text: {
                  '$blocksuite:internal:text$': true,
                  delta: [
                    {
                      insert: 'aaa',
                    },
                  ],
                },
              },
              children: [],
            },
            {
              type: 'block',
              id: 'block:12lDwMD7ec',
              flavour: 'cloud-notes:divider',
              props: {},
              children: [],
            },
          ],
        },
      ],
    };

    const plainText = 'aaa\n---\n';
    const plainTextAdapter = new PlainTextAdapter(createJob(), provider);
    const target = await plainTextAdapter.fromBlockSnapshot({
      snapshot: blockSnapshot,
    });
    expect(target.file).toBe(plainText);
  });

  test('code', async () => {
    const blockSnapshot: BlockSnapshot = {
      type: 'block',
      id: 'block:vu6SK6WJpW',
      flavour: 'cloud-notes:page',
      props: {
        title: {
          '$blocksuite:internal:text$': true,
          delta: [],
        },
      },
      children: [
        {
          type: 'block',
          id: 'block:Tk4gSPocAt',
          flavour: 'cloud-notes:surface',
          props: {
            elements: {},
          },
          children: [],
        },
        {
          type: 'block',
          id: 'block:WfnS5ZDCJT',
          flavour: 'cloud-notes:note',
          props: {
            xywh: '[0,0,800,95]',
            background: DefaultTheme.noteBackgrounColor,
            index: 'a0',
            hidden: false,
            displayMode: NoteDisplayMode.DocAndEdgeless,
          },
          children: [
            {
              type: 'block',
              id: 'block:8hOLxad5Fv',
              flavour: 'cloud-notes:code',
              props: {
                language: 'python',
                text: {
                  '$blocksuite:internal:text$': true,
                  delta: [
                    {
                      insert: 'import this',
                    },
                  ],
                },
              },
              children: [],
            },
          ],
        },
      ],
    };

    const plainText = 'import this\n';
    const plainTextAdapter = new PlainTextAdapter(createJob(), provider);
    const target = await plainTextAdapter.fromBlockSnapshot({
      snapshot: blockSnapshot,
    });
    expect(target.file).toBe(plainText);
  });

  test('special inline delta', async () => {
    const blockSnapshot: BlockSnapshot = {
      type: 'block',
      id: 'block:vu6SK6WJpW',
      flavour: 'cloud-notes:page',
      props: {
        title: {
          '$blocksuite:internal:text$': true,
          delta: [],
        },
      },
      children: [
        {
          type: 'block',
          id: 'block:Tk4gSPocAt',
          flavour: 'cloud-notes:surface',
          props: {
            elements: {},
          },
          children: [],
        },
        {
          type: 'block',
          id: 'block:WfnS5ZDCJT',
          flavour: 'cloud-notes:note',
          props: {
            xywh: '[0,0,800,95]',
            background: DefaultTheme.noteBackgrounColor,
            index: 'a0',
            hidden: false,
            displayMode: NoteDisplayMode.DocAndEdgeless,
          },
          children: [
            {
              type: 'block',
              id: 'block:Bdn8Yvqcny',
              flavour: 'cloud-notes:paragraph',
              props: {
                type: 'text',
                text: {
                  '$blocksuite:internal:text$': true,
                  delta: [
                    {
                      insert: 'aaa',
                      attributes: {
                        link: 'https://notes.diegonmarcos.com/',
                      },
                    },
                  ],
                },
              },
              children: [
                {
                  type: 'block',
                  id: 'block:72SMa5mdLy',
                  flavour: 'cloud-notes:paragraph',
                  props: {
                    type: 'text',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: '',
                          attributes: {
                            reference: {
                              type: 'LinkedPage',
                              pageId: 'deadbeef',
                              params: {
                                mode: 'page',
                                blockIds: ['abc', '123'],
                                elementIds: ['def', '456'],
                                databaseId: 'deadbeef',
                                databaseRowId: '123',
                              },
                            },
                          },
                        },
                      ],
                    },
                  },
                  children: [],
                },
                {
                  type: 'block',
                  id: 'block:f-Z6nRrGK_',
                  flavour: 'cloud-notes:paragraph',
                  props: {
                    type: 'text',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: ' ',
                          attributes: {
                            latex: 'E=mc^2',
                          },
                        },
                      ],
                    },
                  },
                  children: [],
                },
              ],
            },
          ],
        },
      ],
    };
    const middleware: TransformerMiddleware = ({ adapterConfigs }) => {
      adapterConfigs.set('title:deadbeef', 'test');
      adapterConfigs.set('docLinkBaseUrl', 'https://example.com');
    };
    const plainTextAdapter = new PlainTextAdapter(
      createJob([middleware]),
      provider
    );

    const plainText =
      'aaa: https://notes.diegonmarcos.com/\ntest: https://example.com/deadbeef?mode=page&blockIds=abc%2C123&elementIds=def%2C456&databaseId=deadbeef&databaseRowId=123\nE=mc^2\n';
    const target = await plainTextAdapter.fromBlockSnapshot({
      snapshot: blockSnapshot,
    });
    expect(target.file).toBe(plainText);
  });

  describe('embed link block', () => {
    const embedTestCases = [
      {
        name: 'bookmark',
        flavour: 'cloud-notes:bookmark',
        url: 'https://example.com',
        title: 'example',
        plainText: '[example](https://example.com)\n',
      },
      {
        name: 'embed github',
        flavour: 'cloud-notes:embed-github',
        url: 'https://github.com/toeverything/blocksuite/pull/66666',
        title: 'example github pr title',
        plainText:
          '[example github pr title](https://github.com/toeverything/blocksuite/pull/66666)\n',
      },
      {
        name: 'embed figma',
        flavour: 'cloud-notes:embed-figma',
        url: 'https://www.figma.com/file/1234567890',
        title: 'example figma title',
        plainText:
          '[example figma title](https://www.figma.com/file/1234567890)\n',
      },
      {
        name: 'embed youtube',
        flavour: 'cloud-notes:embed-youtube',
        url: 'https://www.youtube.com/watch?v=1234567890',
        title: 'example youtube title',
        plainText:
          '[example youtube title](https://www.youtube.com/watch?v=1234567890)\n',
      },
      {
        name: 'embed loom',
        flavour: 'cloud-notes:embed-loom',
        url: 'https://www.loom.com/share/1234567890',
        title: 'example loom title',
        plainText:
          '[example loom title](https://www.loom.com/share/1234567890)\n',
      },
    ];

    for (const testCase of embedTestCases) {
      test(testCase.name, async () => {
        const blockSnapshot: BlockSnapshot = {
          type: 'block',
          id: 'block:vu6SK6WJpW',
          flavour: 'cloud-notes:page',
          props: {
            title: {
              '$blocksuite:internal:text$': true,
              delta: [],
            },
          },
          children: [
            {
              type: 'block',
              id: 'block:Tk4gSPocAt',
              flavour: 'cloud-notes:surface',
              props: {
                elements: {},
              },
              children: [],
            },
            {
              type: 'block',
              id: 'block:WfnS5ZDCJT',
              flavour: 'cloud-notes:note',
              props: {
                xywh: '[0,0,800,95]',
                background: DefaultTheme.noteBackgrounColor,
                index: 'a0',
                hidden: false,
                displayMode: NoteDisplayMode.DocAndEdgeless,
              },
              children: [
                {
                  type: 'block',
                  id: 'block:Bdn8Yvqcny',
                  flavour: testCase.flavour,
                  props: {
                    url: testCase.url,
                    title: testCase.title,
                  },
                  children: [],
                },
              ],
            },
          ],
        };

        const plainTextAdapter = new PlainTextAdapter(createJob(), provider);
        const target = await plainTextAdapter.fromBlockSnapshot({
          snapshot: blockSnapshot,
        });
        expect(target.file).toBe(testCase.plainText);
      });
    }

    test('linked doc block', async () => {
      const blockSnapShot: BlockSnapshot = {
        type: 'block',
        id: 'VChAZIX7DM',
        flavour: 'cloud-notes:page',
        version: 2,
        props: {
          title: {
            '$blocksuite:internal:text$': true,
            delta: [
              {
                insert: 'Test Doc',
              },
            ],
          },
        },
        children: [
          {
            type: 'block',
            id: 'uRj8gejH4d',
            flavour: 'cloud-notes:surface',
            version: 5,
            props: {
              elements: {},
            },
            children: [],
          },
          {
            type: 'block',
            id: 'AqFoVDUoW9',
            flavour: 'cloud-notes:note',
            version: 1,
            props: {
              xywh: '[0,0,800,95]',
              background: DefaultTheme.noteBackgrounColor,
              index: 'a0',
              hidden: false,
              displayMode: NoteDisplayMode.DocAndEdgeless,
            },
            children: [
              {
                type: 'block',
                id: 'C0sH2Ee6cz-MysVNLNrBt',
                flavour: 'cloud-notes:embed-linked-doc',
                props: {
                  index: 'a0',
                  xywh: '[0,0,0,0]',
                  rotate: 0,
                  pageId: '4T5ObMgEIMII-4Bexyta1',
                  style: 'horizontal',
                  caption: null,
                  params: {
                    mode: 'page',
                    blockIds: ['abc', '123'],
                    elementIds: ['def', '456'],
                    databaseId: 'deadbeef',
                    databaseRowId: '123',
                  },
                },
                children: [],
              },
            ],
          },
        ],
      };

      const middleware: TransformerMiddleware = ({ adapterConfigs }) => {
        adapterConfigs.set('title:4T5ObMgEIMII-4Bexyta1', 'test');
        adapterConfigs.set('docLinkBaseUrl', 'https://example.com');
      };
      const plainText =
        'test: https://example.com/4T5ObMgEIMII-4Bexyta1?mode=page&blockIds=abc%2C123&elementIds=def%2C456&databaseId=deadbeef&databaseRowId=123\n';
      const plainTextAdapter = new PlainTextAdapter(
        createJob([middleware]),
        provider
      );
      const target = await plainTextAdapter.fromBlockSnapshot({
        snapshot: blockSnapShot,
      });
      expect(target.file).toBe(plainText);
    });

    test('synced doc block', async () => {
      // doc -> synced doc block -> deepest synced doc block
      // The deepest synced doc block only export it's title

      const deepestSyncedDocSnapshot: DocSnapshot = {
        type: 'page',
        meta: {
          id: 'deepestSyncedDoc',
          title: 'Deepest Doc',
          createDate: 1715762171116,
          tags: [],
        },
        blocks: {
          type: 'block',
          id: '8WdJmN5FTT',
          flavour: 'cloud-notes:page',
          version: 2,
          props: {
            title: {
              '$blocksuite:internal:text$': true,
              delta: [
                {
                  insert: 'Deepest Doc',
                },
              ],
            },
          },
          children: [
            {
              type: 'block',
              id: 'zVN1EZFuZe',
              flavour: 'cloud-notes:surface',
              version: 5,
              props: {
                elements: {},
              },
              children: [],
            },
            {
              type: 'block',
              id: '2s9sJlphLH',
              flavour: 'cloud-notes:note',
              version: 1,
              props: {
                xywh: '[0,0,800,95]',
                background: DefaultTheme.noteBackgrounColor,
                index: 'a0',
                hidden: false,
                displayMode: 'both',
                edgeless: {
                  style: {
                    borderRadius: 8,
                    borderSize: 4,
                    borderStyle: 'solid',
                    shadowType: '--cloud-notes-note-shadow-box',
                  },
                },
              },
              children: [
                {
                  type: 'block',
                  id: 'vNp5XrR5yw',
                  flavour: 'cloud-notes:paragraph',
                  version: 1,
                  props: {
                    type: 'text',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [],
                    },
                  },
                  children: [],
                },
                {
                  type: 'block',
                  id: 'JTdfSl1ygZ',
                  flavour: 'cloud-notes:paragraph',
                  version: 1,
                  props: {
                    type: 'text',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'Hello, This is deepest doc.',
                        },
                      ],
                    },
                  },
                  children: [],
                },
              ],
            },
          ],
        },
      };

      const syncedDocSnapshot: DocSnapshot = {
        type: 'page',
        meta: {
          id: 'syncedDoc',
          title: 'Synced Doc',
          createDate: 1719212435051,
          tags: [],
        },
        blocks: {
          type: 'block',
          id: 'AGOahFisBN',
          flavour: 'cloud-notes:page',
          version: 2,
          props: {
            title: {
              '$blocksuite:internal:text$': true,
              delta: [
                {
                  insert: 'Synced Doc',
                },
              ],
            },
          },
          children: [
            {
              type: 'block',
              id: 'gfVzx5tGpB',
              flavour: 'cloud-notes:surface',
              version: 5,
              props: {
                elements: {},
              },
              children: [],
            },
            {
              type: 'block',
              id: 'CzEfaUret4',
              flavour: 'cloud-notes:note',
              version: 1,
              props: {
                xywh: '[0,0,800,95]',
                background: '--cloud-notes-note-background-blue',
                index: 'a0',
                hidden: false,
                displayMode: 'both',
                edgeless: {
                  style: {
                    borderRadius: 0,
                    borderSize: 4,
                    borderStyle: 'none',
                    shadowType: '--cloud-notes-note-shadow-sticker',
                  },
                },
              },
              children: [
                {
                  type: 'block',
                  id: 'yFlNufsgke',
                  flavour: 'cloud-notes:paragraph',
                  version: 1,
                  props: {
                    type: 'h1',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'Heading 1',
                        },
                      ],
                    },
                  },
                  children: [],
                },
                {
                  type: 'block',
                  id: 'oMuLcD6XS3',
                  flavour: 'cloud-notes:paragraph',
                  version: 1,
                  props: {
                    type: 'h2',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'heading 2',
                        },
                      ],
                    },
                  },
                  children: [],
                },
                {
                  type: 'block',
                  id: 'PQ8FhGV6VM',
                  flavour: 'cloud-notes:paragraph',
                  version: 1,
                  props: {
                    type: 'text',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'paragraph',
                        },
                      ],
                    },
                  },
                  children: [],
                },
                {
                  type: 'block',
                  id: 'sA9paSrdEN',
                  flavour: 'cloud-notes:paragraph',
                  version: 1,
                  props: {
                    type: 'text',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'strike',
                          attributes: {
                            strike: true,
                          },
                        },
                      ],
                    },
                  },
                  children: [],
                },
                {
                  type: 'block',
                  id: 'DF26giFpKX',
                  flavour: 'cloud-notes:code',
                  version: 1,
                  props: {
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'Hello world!',
                        },
                      ],
                    },
                    language: 'cpp',
                    wrap: false,
                    caption: '',
                  },
                  children: [],
                },
                {
                  type: 'block',
                  id: '-3bbVQTvI2',
                  flavour: 'cloud-notes:embed-synced-doc',
                  version: 1,
                  props: {
                    index: 'a0',
                    xywh: '[0,0,0,0]',
                    rotate: 0,
                    pageId: 'deepestSyncedDoc',
                    style: 'syncedDoc',
                  },
                  children: [],
                },
              ],
            },
          ],
        },
      };

      const syncedDocPlainText =
        'Heading 1\nheading 2\nparagraph\nstrike\nHello world!\n';

      const docSnapShot: DocSnapshot = {
        type: 'page',
        meta: {
          id: 'y5nsrywQtr',
          title: 'Test Doc',
          createDate: 1719222172042,
          tags: [],
        },
        blocks: {
          type: 'block',
          id: 'VChAZIX7DM',
          flavour: 'cloud-notes:page',
          version: 2,
          props: {
            title: {
              '$blocksuite:internal:text$': true,
              delta: [
                {
                  insert: 'Test Doc',
                },
              ],
            },
          },
          children: [
            {
              type: 'block',
              id: 'uRj8gejH4d',
              flavour: 'cloud-notes:surface',
              version: 5,
              props: {
                elements: {},
              },
              children: [],
            },
            {
              type: 'block',
              id: 'AqFoVDUoW9',
              flavour: 'cloud-notes:note',
              version: 1,
              props: {
                xywh: '[0,0,800,95]',
                background: '--cloud-notes-note-background-blue',
                index: 'a0',
                hidden: false,
                displayMode: 'both',
                edgeless: {
                  style: {
                    borderRadius: 0,
                    borderSize: 4,
                    borderStyle: 'none',
                    shadowType: '--cloud-notes-note-shadow-sticker',
                  },
                },
              },
              children: [
                {
                  type: 'block',
                  id: 'cWBI4UGTqh',
                  flavour: 'cloud-notes:paragraph',
                  version: 1,
                  props: {
                    type: 'text',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'Hello',
                        },
                      ],
                    },
                  },
                  children: [],
                },
                {
                  type: 'block',
                  id: 'AqFoVxas19',
                  flavour: 'cloud-notes:embed-synced-doc',
                  version: 1,
                  props: {
                    index: 'a0',
                    xywh: '[0,0,0,0]',
                    rotate: 0,
                    pageId: 'syncedDoc',
                    style: 'syncedDoc',
                  },
                  children: [],
                },
                {
                  type: 'block',
                  id: 'Db976U9v18',
                  flavour: 'cloud-notes:paragraph',
                  version: 1,
                  props: {
                    type: 'text',
                    text: {
                      '$blocksuite:internal:text$': true,
                      delta: [
                        {
                          insert: 'World!',
                        },
                      ],
                    },
                  },
                  children: [],
                },
              ],
            },
          ],
        },
      };

      const docPlainText = `Test Doc\n\nHello\n${syncedDocPlainText}Deepest Doc\nWorld!\n`;
      const job = createJob([embedSyncedDocMiddleware('content')]);

      // workaround for adding docs to collection
      await job.snapshotToDoc(deepestSyncedDocSnapshot);
      await job.snapshotToDoc(syncedDocSnapshot);
      await job.snapshotToDoc(docSnapShot);

      const mdAdapter = new PlainTextAdapter(job, provider);
      const target = await mdAdapter.fromDocSnapshot({
        snapshot: docSnapShot,
      });
      expect(target.file).toBe(docPlainText);
    });
  });

  test('latex block', async () => {
    const blockSnapshot: BlockSnapshot = {
      type: 'block',
      id: 'matchesReplaceMap[0]',
      flavour: 'cloud-notes:note',
      props: {
        xywh: '[0,0,800,95]',
        background: DefaultTheme.noteBackgrounColor,
        index: 'a0',
        hidden: false,
        displayMode: NoteDisplayMode.DocAndEdgeless,
      },
      children: [
        {
          type: 'block',
          id: 'matchesReplaceMap[1]',
          flavour: 'cloud-notes:latex',
          props: {
            latex: 'E=mc^2',
          },
          children: [],
        },
      ],
    };

    const plainText = 'LaTex, with value: E=mc^2\n';
    const plainTextAdapter = new PlainTextAdapter(createJob(), provider);
    const target = await plainTextAdapter.fromBlockSnapshot({
      snapshot: blockSnapshot,
    });
    expect(target.file).toBe(plainText);
  });

  test('table', async () => {
    const blockSnapshot: BlockSnapshot = {
      type: 'block',
      id: 'block:8Wb7CSJ9Qe',
      flavour: 'cloud-notes:database',
      props: {
        cells: {
          'block:P_-Wg7Rg9O': {
            'block:qyo8q9VPWU': {
              columnId: 'block:qyo8q9VPWU',
              value: 'TKip9uc7Yx',
            },
            'block:5cglrBmAr3': {
              columnId: 'block:5cglrBmAr3',
              value: 1702598400000,
            },
            'block:8Fa0JQe7WY': {
              columnId: 'block:8Fa0JQe7WY',
              value: 1,
            },
            'block:5ej6StPuF_': {
              columnId: 'block:5ej6StPuF_',
              value: 65,
            },
            'block:DPhZ6JBziD': {
              columnId: 'block:DPhZ6JBziD',
              value: ['-2_QD3GZT1', '73UrEZWaKk'],
            },
            'block:O8dpIDiP7-': {
              columnId: 'block:O8dpIDiP7-',
              value: {
                '$blocksuite:internal:text$': true,
                delta: [
                  {
                    insert: 'test2',
                    attributes: {
                      link: 'https://google.com',
                    },
                  },
                ],
              },
            },
            'block:U8lPD59MkF': {
              columnId: 'block:U8lPD59MkF',
              value: 'https://google.com',
            },
            'block:-DT7B0TafG': {
              columnId: 'block:-DT7B0TafG',
              value: true,
            },
          },
          'block:0vhfgcHtPF': {
            'block:qyo8q9VPWU': {
              columnId: 'block:qyo8q9VPWU',
              value: 'F2bgsaE3X2',
            },
            'block:O8dpIDiP7-': {
              columnId: 'block:O8dpIDiP7-',
              value: {
                '$blocksuite:internal:text$': true,
                delta: [
                  {
                    insert: 'test1',
                  },
                ],
              },
            },
            'block:5cglrBmAr3': {
              columnId: 'block:5cglrBmAr3',
              value: 1703030400000,
            },
          },
          'block:b4_02QXMAM': {
            'block:qyo8q9VPWU': {
              columnId: 'block:qyo8q9VPWU',
              value: 'y3O1A2IHHu',
            },
          },
          'block:W_eirvg7EJ': {
            'block:qyo8q9VPWU': {
              columnId: 'block:qyo8q9VPWU',
            },
          },
        },
        columns: [
          {
            type: 'title',
            name: 'Title',
            data: {},
            id: 'block:2VfUaitjf9',
          },
          {
            type: 'select',
            name: 'Status',
            data: {
              options: [
                {
                  id: 'TKip9uc7Yx',
                  color: 'var(--cloud-notes-tag-white)',
                  value: 'TODO',
                },
                {
                  id: 'F2bgsaE3X2',
                  color: 'var(--cloud-notes-tag-green)',
                  value: 'In Progress',
                },
                {
                  id: 'y3O1A2IHHu',
                  color: 'var(--cloud-notes-tag-gray)',
                  value: 'Done',
                },
              ],
            },
            id: 'block:qyo8q9VPWU',
          },
          {
            type: 'date',
            name: 'Date',
            data: {},
            id: 'block:5cglrBmAr3',
          },
          {
            type: 'number',
            name: 'Number',
            data: {
              decimal: 0,
            },
            id: 'block:8Fa0JQe7WY',
          },
          {
            type: 'progress',
            name: 'Progress',
            data: {},
            id: 'block:5ej6StPuF_',
          },
          {
            type: 'multi-select',
            name: 'MultiSelect',
            data: {
              options: [
                {
                  id: '73UrEZWaKk',
                  value: 'test2',
                  color: 'var(--cloud-notes-tag-purple)',
                },
                {
                  id: '-2_QD3GZT1',
                  value: 'test1',
                  color: 'var(--cloud-notes-tag-teal)',
                },
              ],
            },
            id: 'block:DPhZ6JBziD',
          },
          {
            type: 'rich-text',
            name: 'RichText',
            data: {},
            id: 'block:O8dpIDiP7-',
          },
          {
            type: 'link',
            name: 'Link',
            data: {},
            id: 'block:U8lPD59MkF',
          },
          {
            type: 'checkbox',
            name: 'Checkbox',
            data: {},
            id: 'block:-DT7B0TafG',
          },
        ],
      },
      children: [
        {
          type: 'block',
          id: 'block:P_-Wg7Rg9O',
          flavour: 'cloud-notes:paragraph',
          props: {
            type: 'text',
            text: {
              '$blocksuite:internal:text$': true,
              delta: [
                {
                  insert: 'Task 1',
                },
              ],
            },
          },
          children: [],
        },
        {
          type: 'block',
          id: 'block:0vhfgcHtPF',
          flavour: 'cloud-notes:paragraph',
          props: {
            type: 'text',
            text: {
              '$blocksuite:internal:text$': true,
              delta: [
                {
                  insert: 'Task 2',
                },
              ],
            },
          },
          children: [],
        },
      ],
    };

    const plainText = `\
| Title  | Status      | Date       | Number | Progress | MultiSelect | RichText                  | Link               | Checkbox |
| ------ | ----------- | ---------- | ------ | -------- | ----------- | ------------------------- | ------------------ | -------- |
| Task 1 | TODO        | 2023-12-15 | 1      | 65       | test1,test2 | test2: https://google.com | https://google.com | True     |
| Task 2 | In Progress | 2023-12-20 |        |          |             | test1                     |                    |          |
`;
    const plainTextAdapter = new PlainTextAdapter(createJob(), provider);
    const target = await plainTextAdapter.fromBlockSnapshot({
      snapshot: blockSnapshot,
    });
    expect(target.file).toBe(plainText);
  });
});
