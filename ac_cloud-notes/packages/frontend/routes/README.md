# Routes

## Usage

### Path Factories

```ts
import { FACTORIES } from '@cloud-notes/routes';

const path = FACTORIES.workspace.doc({ workspaceId: '123', docId: '456' });
//                                     ^^^^ with typecheck
```

### Register router

```tsx
import { ROUTES } from '@cloud-notes/routes';

function Routes() {
  return <Router path={ROUTES.admin.index} element={} />;
}
```

### Path Parameter

```ts
import { RouteParamsTypes } from '@cloud-notes/routes';

function Doc() {
  const { workspaceId, docId } =
    useParams<RouteParamsTypes['workspace']['doc']>();
}

function Attachment() {
  const { workspaceId, docId, attachmentId } =
    useParams<RouteParamsTypes['workspace']['doc']['attachment']>();
}
```
