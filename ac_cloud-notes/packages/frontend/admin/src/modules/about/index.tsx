import { ScrollArea } from '@cloud-notes/admin/components/ui/scroll-area';

import { Header } from '../header';
import { AboutCloudNotes } from './about';

export function ConfigPage() {
  return (
    <div className="h-dvh flex-1 space-y-1 flex-col flex">
      <Header title="Server" />
      <ScrollArea>
        <AboutCloudNotes />
      </ScrollArea>
    </div>
  );
}

export { ConfigPage as Component };
