import { cssVarV2 } from '@toeverything/theme/v2';

export type SelectOptionColor = {
  oldColor: string;
  color: string;
  name: string;
};
export const selectOptionColors: SelectOptionColor[] = [
  {
    oldColor: 'var(--cloud-notes-tag-red)',
    color: cssVarV2('chip/label/red'),
    name: 'Red',
  },
  {
    oldColor: 'var(--cloud-notes-tag-pink)',
    color: cssVarV2('chip/label/magenta'),
    name: 'Magenta',
  },
  {
    oldColor: 'var(--cloud-notes-tag-orange)',
    color: cssVarV2('chip/label/orange'),
    name: 'Orange',
  },
  {
    oldColor: 'var(--cloud-notes-tag-yellow)',
    color: cssVarV2('chip/label/yellow'),
    name: 'Yellow',
  },
  {
    oldColor: 'var(--cloud-notes-tag-green)',
    color: cssVarV2('chip/label/green'),
    name: 'Green',
  },
  {
    oldColor: 'var(--cloud-notes-tag-teal)',
    color: cssVarV2('chip/label/teal'),
    name: 'Teal',
  },
  {
    oldColor: 'var(--cloud-notes-tag-blue)',
    color: cssVarV2('chip/label/blue'),
    name: 'Blue',
  },
  {
    oldColor: 'var(--cloud-notes-tag-purple)',
    color: cssVarV2('chip/label/purple'),
    name: 'Purple',
  },
  {
    oldColor: 'var(--cloud-notes-tag-gray)',
    color: cssVarV2('chip/label/grey'),
    name: 'Grey',
  },
  {
    oldColor: 'var(--cloud-notes-tag-white)',
    color: cssVarV2('chip/label/white'),
    name: 'White',
  },
];

const oldColorMap = Object.fromEntries(
  selectOptionColors.map(tag => [tag.oldColor, tag.color])
);

export const getColorByColor = (color: string) => {
  if (color.startsWith('--cloud-notes-tag')) {
    return oldColorMap[color] ?? color;
  }
  return color;
};

/** select tag color poll */
const selectTagColorPoll = selectOptionColors.map(color => color.color);

function tagColorHelper() {
  let colors = [...selectTagColorPoll];
  return (): string => {
    if (colors.length === 0) {
      colors = [...selectTagColorPoll];
    }
    const index = Math.floor(Math.random() * colors.length);
    const color = colors.splice(index, 1)[0];
    if (!color) return '';
    return color;
  };
}

export const getTagColor = tagColorHelper();
