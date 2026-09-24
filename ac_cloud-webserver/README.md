# cloud-webserver

my-webserver on the phone. #288, Route D.

The binary is the same glibc Node SEA that cloud-u-linux publishes. It asks for
`/lib/ld-linux-aarch64.so.1`, and bionic has no such file. So this APK does not
rebuild Node. It ships the glibc the binary already wants: the proot + glibc
rootfs that `ac_cloud-nix-on-droid` already bakes (#348), started by
`bin/proot-static`, which is bionic and runs natively.

| Asset | Where it comes from |
|---|---|
| `rootfs.zip` | The pin in `../ac_cloud-nix-on-droid/build.json::forks.nixdroid.bootstrap`. It is referenced from here, never copied. |
| `my-webserver` | `build.json::bake.binary`: an immutable cloud-u-linux tag, with a sha256 per ABI. |
| `webserver-runtime.json` | Derived at build time by `app/resolve_runtime.py` from the binary's `PT_INTERP`/`DT_NEEDED` and the rootfs listing. |

No nix closure is evaluated. That is why #203's fixed-output hash mismatch
cannot break this build.

On first start the app unpacks the rootfs and the binary into `filesDir`. It
then runs `proot-static --link2symlink -b <rootfs>/nix:/nix -b <glibc>:/lib my-webserver <port> <root>`
as a foreground service and shows `http://127.0.0.1:<port>/` in a WebView.
targetSdk is held at 28, because from API 29 SELinux refuses `execve()` of
anything in the app's data directory.

Tester: `test/test-webserver-rootfs.sh`.
