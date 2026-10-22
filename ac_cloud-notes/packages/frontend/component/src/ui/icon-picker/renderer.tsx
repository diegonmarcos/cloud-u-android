import type { ReactNode } from 'react';

import { CloudNotesIconRenderer } from './renderer/cloud-notes-icon';
import { type IconData, IconType, isCloudNotesIconType } from './type';

export const IconRenderer = ({
  data,
  fallback,
  ...props
}: {
  data?: IconData;
  fallback?: ReactNode;
}) => {
  if (!data) {
    return fallback ?? null;
  }

  if (data.type === IconType.Emoji && data.unicode) {
    return data.unicode;
  }
  if (isCloudNotesIconType(data.type) && 'name' in data && data.name) {
    return (
      <CloudNotesIconRenderer name={data.name} color={data.color} {...props} />
    );
  }
  if (data.type === IconType.Blob) {
    // Not supported yet
    return null;
  }

  return fallback ?? null;
};
