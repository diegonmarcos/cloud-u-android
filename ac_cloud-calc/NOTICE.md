# Cloud Calc — licence notice

Cloud Calc is two APKs: this app (`Cloud-Calc.apk`, the screens) and the engine it binds
(`Cloud-Lib-Calc.apk`, `ab_cloud-libs-shared/libs/calc`). Every third-party component, where it
ships, and its licence:

## In Cloud-Lib-Calc.apk (statically linked into `libqalc.so`)

| Component | Version | Licence | Source |
|---|---|---|---|
| libqalculate (the engine of Qalculate!) | 5.12.0 | GPL-2.0-or-later | https://github.com/Qalculate/libqalculate |
| GNU MP (GMP) | 6.3.0 | LGPL-3.0-or-later OR GPL-2.0-or-later | https://gmplib.org |
| GNU MPFR | 4.2.2 | LGPL-3.0-or-later | https://www.mpfr.org |
| libxml2 | 2.13.8 | MIT | https://gitlab.gnome.org/GNOME/libxml2 |
| LLVM libc++ (NDK 28.0.13004108) | — | Apache-2.0 WITH LLVM-exception | https://github.com/llvm/llvm-project |

The combined `libqalc.so` is distributed under **GPL-3.0-or-later**. Its complete corresponding
source is this repository: `ab_cloud-libs-shared/libs/calc/native/` (our code and the one patch
applied to libqalculate) plus the exact upstream tarballs pinned by sha256 in
`ab_cloud-libs-shared/libs/calc/data/qalc-native.json`; `ship-lib-calc-native.yml` is the build.
libqalculate's unit, constant and currency definitions (CODATA constants, ECB reference rates)
are its own data, compiled in.

## In Cloud-Calc.apk

| Component | Licence |
|---|---|
| AndroidX, Jetpack Compose, Material 3, Material icons (extended) | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 |
| Fleet libraries (libs:core, libs:devtools, libs:bottomnav) | this repository |

The sound meter's signal processing (radix-2 FFT, Hann window, IEC 61672-1 A-weighting, dBFS)
is written in `app/src/main/java/com/diegonmarcos/cloudcalc/Dsp.kt` from the published
formulas; no code was taken from phyphox or OpeNoise (both GPL-3.0).

## Evaluated and not used (#767)

| Candidate | Licence | Why not |
|---|---|---|
| sadellie/unitto (NumberHub lineage) | GPL-3.0 | A 25-module Kotlin Multiplatform app (`sharedApp`, `androidApp`, Room, remote) with its own evaluator (`core:evaluatto`, `kt-math`). Vendoring it means a second maths engine compiled into the app — the non-GUI logic #763 keeps out of apps — and grafting the fleet shell into a different app architecture. Every mode it has is covered by libqalculate here. |
| FossifyOrg/Calculator | GPL-3.0 | A standard calculator over EvalEx (BigDecimal, no units, no CAS) plus Fossify commons and Room: a subset of libqalculate. |
| numbat | MIT / Apache-2.0 | Dimension-safe unit arithmetic is already libqalculate's (`500 W * 2 h to kWh` is a golden row); a second engine with a second unit table for one overlapping feature. |
