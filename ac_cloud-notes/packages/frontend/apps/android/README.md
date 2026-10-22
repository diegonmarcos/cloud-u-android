# Android

Cloud Notes Android app.

## Setup

- set CARGO_HOME to your system environment
- add

  `rust.cargoCommand={replace_with_your_own_cargo_home_absolute_path}/bin/cargo`

  `rust.rustcCommand={replace_with_your_own_cargo_home_absolute_path}/bin/rustc`

  to App/local.properties

## Build

- yarn install
- BUILD_TYPE=canary PUBLIC_PATH="/" yarn cloud-notes @cloud-notes/android build
- yarn cloud-notes @cloud-notes/android cap sync
- yarn cloud-notes @cloud-notes/android cap open android
