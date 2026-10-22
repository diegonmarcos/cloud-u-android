import type { QuickSearchItem } from '@cloud-notes/core/modules/quicksearch';
import { HighlightText } from '@cloud-notes/core/modules/quicksearch/views/highlight-text';
import { isI18nString, useI18n } from '@cloud-notes/i18n';

export interface SearchResLabelProps {
  item: QuickSearchItem;
}
export const SearchResLabel = ({ item }: SearchResLabelProps) => {
  const i18n = useI18n();

  return (
    <HighlightText
      text={i18n.t(isI18nString(item.label) ? item.label : item.label.title)}
      start="<b>"
      end="</b>"
    />
  );
};
