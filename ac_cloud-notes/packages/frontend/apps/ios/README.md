# iOS

Cloud Notes iOS app.

## Build

- `yarn install`
- `BUILD_TYPE=canary PUBLIC_PATH="/" yarn cloud-notes @cloud-notes/ios build`
- `yarn cloud-notes @cloud-notes/ios cap sync`
- `yarn cloud-notes @cloud-notes/ios cap open ios`

## Live Reload

> Capacitor doc: https://capacitorjs.com/docs/guides/live-reload#using-with-framework-clis

- `yarn install`
- `yarn dev`
  - select `ios` for the "Distribution" option
- `yarn cloud-notes @cloud-notes/ios sync:dev`
- `yarn cloud-notes @cloud-notes/ios cap open ios`
