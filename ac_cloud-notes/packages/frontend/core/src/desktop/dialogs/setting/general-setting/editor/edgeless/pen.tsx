import { MenuItem, MenuTrigger, Slider } from '@cloud-notes/component';
import { SettingRow } from '@cloud-notes/component/setting-components';
import { EditorSettingService } from '@cloud-notes/core/modules/editor-setting';
import { useI18n } from '@cloud-notes/i18n';
import { getSurfaceBlock } from '@blocksuite/cloud-notes/blocks/surface';
import { DefaultTheme } from '@blocksuite/cloud-notes/model';
import type { Store } from '@blocksuite/cloud-notes/store';
import { useFramework, useLiveData } from '@toeverything/infra';
import { isEqual } from 'lodash-es';
import { useCallback, useMemo } from 'react';

import { DropdownMenu } from '../menu';
import { menuTrigger } from '../style.css';
import { usePalettes } from '../utils';
import { Point } from './point';
import { EdgelessSnapshot } from './snapshot';

export const PenSettings = () => {
  const t = useI18n();
  const framework = useFramework();
  const { editorSetting } = framework.get(EditorSettingService);
  const settings = useLiveData(editorSetting.settings$);
  const { palettes, getCurrentColor } = usePalettes(
    DefaultTheme.StrokeColorShortPalettes,
    DefaultTheme.black
  );

  const currentColor = useMemo(() => {
    return getCurrentColor(settings.brush.color);
  }, [getCurrentColor, settings.brush.color]);

  const colorItems = useMemo(() => {
    const { color } = settings.brush;
    return palettes.map(({ key, value, resolvedValue }) => {
      const handler = () => {
        editorSetting.set('brush', { color: value });
      };
      const isSelected = isEqual(color, value);
      return (
        <MenuItem
          key={key}
          onSelect={handler}
          selected={isSelected}
          prefix={<Point color={resolvedValue} />}
        >
          {key}
        </MenuItem>
      );
    });
  }, [editorSetting, settings.brush, palettes]);

  const borderThickness = settings.brush.lineWidth;
  const setBorderThickness = useCallback(
    (value: number[]) => {
      editorSetting.set('brush', {
        lineWidth: value[0],
      });
    },
    [editorSetting]
  );

  const getElements = useCallback((doc: Store) => {
    const surface = getSurfaceBlock(doc);
    return surface?.getElementsByType('brush') || [];
  }, []);

  return (
    <>
      <EdgelessSnapshot
        title={t['com.cloud-notes.settings.editorSettings.edgeless.pen']()}
        docName="pen"
        keyName="brush"
        getElements={getElements}
      />
      <SettingRow
        name={t['com.cloud-notes.settings.editorSettings.edgeless.pen.color']()}
        desc={''}
      >
        {currentColor ? (
          <DropdownMenu
            items={colorItems}
            trigger={
              <MenuTrigger
                className={menuTrigger}
                prefix={<Point color={currentColor.resolvedValue} />}
              >
                {currentColor.key}
              </MenuTrigger>
            }
          />
        ) : null}
      </SettingRow>
      <SettingRow
        name={t['com.cloud-notes.settings.editorSettings.edgeless.pen.thickness']()}
        desc={''}
      >
        <Slider
          value={[borderThickness]}
          onValueChange={setBorderThickness}
          min={2}
          max={12}
          step={2}
          nodes={[2, 4, 6, 8, 10, 12]}
        />
      </SettingRow>
    </>
  );
};
