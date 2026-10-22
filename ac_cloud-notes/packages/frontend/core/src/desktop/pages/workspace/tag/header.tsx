import { ExplorerDisplayMenuButton } from '@cloud-notes/core/components/explorer/display-menu';
import { ExplorerNavigation } from '@cloud-notes/core/components/explorer/header/navigation';
import type { ExplorerDisplayPreference } from '@cloud-notes/core/components/explorer/types';
import { Header } from '@cloud-notes/core/components/pure/header';

export const TagDetailHeader = ({
  displayPreference,
  onDisplayPreferenceChange,
}: {
  displayPreference: ExplorerDisplayPreference;
  onDisplayPreferenceChange: (
    displayPreference: ExplorerDisplayPreference
  ) => void;
}) => {
  return (
    <Header
      left={<ExplorerNavigation active={'tags'} />}
      right={
        <ExplorerDisplayMenuButton
          displayPreference={displayPreference}
          onDisplayPreferenceChange={onDisplayPreferenceChange}
        />
      }
    />
  );
};
