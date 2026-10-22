import { useThemeColorV2 } from '@cloud-notes/component';

import { AllDocsHeader, CollectionList } from '../../../views';

export const Component = () => {
  useThemeColorV2('layer/background/mobile/primary');
  return (
    <>
      <AllDocsHeader />
      <CollectionList />
    </>
  );
};
