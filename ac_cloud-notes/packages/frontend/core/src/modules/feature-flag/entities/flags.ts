import { Entity, LiveData, migrateLegacyLocalStorage } from '@toeverything/infra';
import { NEVER } from 'rxjs';

import type { GlobalStateService } from '../../storage';
import { CLOUD_NOTES_FLAGS } from '../constant';
import type { FlagInfo } from '../types';

const FLAG_PREFIX = 'cloud-notes-flag:';
// flags used to be stored under the app's earlier name; carry them over once
migrateLegacyLocalStorage();

export type Flag<F extends FlagInfo = FlagInfo> = {
  readonly value: F['defaultState'] extends boolean
    ? boolean
    : boolean | undefined;
  set: (value: boolean) => void;
  $: F['defaultState'] extends boolean
    ? LiveData<boolean>
    : LiveData<boolean> | LiveData<boolean | undefined>;
} & F;

export class Flags extends Entity {
  private readonly globalState = this.globalStateService.globalState;

  constructor(private readonly globalStateService: GlobalStateService) {
    super();

    Object.entries(CLOUD_NOTES_FLAGS).forEach(([flagKey, flag]) => {
      const configurable = flag.configurable ?? true;
      const defaultState =
        'defaultState' in flag ? flag.defaultState : undefined;
      const getValue = () => {
        return configurable
          ? (this.globalState.get<boolean>(FLAG_PREFIX + flagKey) ??
              defaultState)
          : defaultState;
      };
      const item = {
        ...flag,
        get value() {
          return getValue();
        },
        set: (value: boolean) => {
          if (!configurable) {
            return;
          }
          this.globalState.set(FLAG_PREFIX + flagKey, value);
        },
        $: configurable
          ? LiveData.from<boolean | undefined>(
              this.globalState.watch<boolean>(FLAG_PREFIX + flagKey),
              undefined
            ).map(value => value ?? defaultState)
          : LiveData.from(NEVER, defaultState),
      } as Flag<typeof flag>;
      Object.defineProperty(this, flagKey, {
        get: () => {
          return item;
        },
      });
    });
  }
}

export type FlagsExt = Flags & {
  [K in keyof CLOUD_NOTES_FLAGS]: Flag<CLOUD_NOTES_FLAGS[K]>;
};
