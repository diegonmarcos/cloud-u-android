# Cloud Notes Test Tools

## Structured Document Creation

`cloud-notes-template.ts` provides a concise way to create test documents, using a html-like syntax.

### Basic Usage

```typescript
import { cloud-notes } from '@blocksuite/cloud-notes-shared/test-utils';

// Create a simple document
const doc = cloud-notes`
  <cloud-notes-page>
    <cloud-notes-note>
      <cloud-notes-paragraph>Hello, World!</cloud-notes-paragraph>
    </cloud-notes-note>
  </cloud-notes-page>
`;
```

### Complex Structure Example

```typescript
// Create a document with multiple notes and paragraphs
const doc = cloud-notes`
  <cloud-notes-page title="My Test Page">
    <cloud-notes-note>
      <cloud-notes-paragraph>First paragraph</cloud-notes-paragraph>
      <cloud-notes-paragraph>Second paragraph</cloud-notes-paragraph>
    </cloud-notes-note>
    <cloud-notes-note>
      <cloud-notes-paragraph>Another note</cloud-notes-paragraph>
    </cloud-notes-note>
  </cloud-notes-page>
`;
```

### Application in Tests

This tool is particularly suitable for creating documents with specific structures in test cases:

```typescript
import { describe, expect, it } from 'vitest';
import { cloud-notes } from '../__tests__/utils/cloud-notes-template';

describe('My Test', () => {
  it('should correctly handle document structure', () => {
    const doc = cloud-notes`
      <cloud-notes-page>
        <cloud-notes-note>
          <cloud-notes-paragraph>Test content</cloud-notes-paragraph>
        </cloud-notes-note>
      </cloud-notes-page>
    `;

    // Get blocks
    const pages = doc.getBlocksByFlavour('affine:page');
    const notes = doc.getBlocksByFlavour('affine:note');
    const paragraphs = doc.getBlocksByFlavour('affine:paragraph');

    expect(pages.length).toBe(1);
    expect(notes.length).toBe(1);
    expect(paragraphs.length).toBe(1);

    // Perform more tests here...
  });
});
```

### Supported Block Types

Currently supports the following block types:

- `cloud-notes-page` → `affine:page`
- `cloud-notes-note` → `affine:note`
- `cloud-notes-paragraph` → `affine:paragraph`
- `cloud-notes-list` → `affine:list`
- `cloud-notes-image` → `affine:image`
