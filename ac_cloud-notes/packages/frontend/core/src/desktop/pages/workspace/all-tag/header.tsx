import { ExplorerNavigation } from '@cloud-notes/core/components/explorer/header/navigation';
import { Header } from '@cloud-notes/core/components/pure/header';

export const AllTagHeader = () => {
  return <Header left={<ExplorerNavigation active={'tags'} />} />;
};
