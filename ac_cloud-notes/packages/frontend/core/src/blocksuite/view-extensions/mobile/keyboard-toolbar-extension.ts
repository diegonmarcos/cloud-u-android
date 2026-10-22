import { VirtualKeyboardProvider } from '@cloud-notes/core/mobile/modules/virtual-keyboard';
import { globalVars } from '@cloud-notes/core/mobile/styles/variables.css';
import type { Container } from '@blocksuite/cloud-notes/global/di';
import { DisposableGroup } from '@blocksuite/cloud-notes/global/disposable';
import {
  VirtualKeyboardProvider as BSVirtualKeyboardProvider,
  type VirtualKeyboardProviderWithAction,
} from '@blocksuite/cloud-notes/shared/services';
import { LifeCycleWatcher } from '@blocksuite/cloud-notes/std';
import type { ExtensionType } from '@blocksuite/cloud-notes/store';
import { batch, signal } from '@preact/signals-core';
import type { FrameworkProvider } from '@toeverything/infra';

export function KeyboardToolbarExtension(
  framework: FrameworkProvider
): ExtensionType {
  const cloudNotesVirtualKeyboardProvider = framework.get(VirtualKeyboardProvider);

  class BSVirtualKeyboardService
    extends LifeCycleWatcher
    implements BSVirtualKeyboardProvider
  {
    static override key = BSVirtualKeyboardProvider.identifierName;

    private readonly _disposables = new DisposableGroup();

    readonly visible$ = signal(false);

    readonly height$ = signal(0);

    readonly staticHeight$ = signal(0);

    readonly appTabSafeArea$ = signal(`calc(${globalVars.appTabSafeArea})`);

    static override setup(di: Container) {
      super.setup(di);
      di.addImpl(BSVirtualKeyboardProvider, provider => {
        return provider.get(this);
      });
    }

    override mounted() {
      this._disposables.add(
        cloudNotesVirtualKeyboardProvider.onChange(
          ({ visible, height, overlaysContent }) => {
            const layoutHeight = overlaysContent === false ? 0 : height;

            batch(() => {
              if (visible && this.staticHeight$.peek() !== height) {
                this.staticHeight$.value = height;
              }
              this.visible$.value = visible;
              this.height$.value = layoutHeight;
            });
          }
        )
      );
    }

    override unmounted() {
      this._disposables.dispose();
    }
  }

  if ('show' in cloudNotesVirtualKeyboardProvider) {
    const providerWithAction = cloudNotesVirtualKeyboardProvider;

    class BSVirtualKeyboardServiceWithShowAndHide
      extends BSVirtualKeyboardService
      implements VirtualKeyboardProviderWithAction
    {
      show() {
        providerWithAction.show();
      }

      hide() {
        providerWithAction.hide();
      }
    }

    return BSVirtualKeyboardServiceWithShowAndHide;
  }

  return BSVirtualKeyboardService;
}
