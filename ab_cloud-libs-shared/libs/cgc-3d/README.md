# libs:cgc-3d — the CG apps' 3D model viewer and editor

Shared by reference (`build.json::modules` `dir`), compile-in only (excluded from the lib-APK shelf). Open source end to end.
First consumer: CG-Omniverse ▸ Avatar (#1373). Planned: Galaxy, Cloud Nav's space view.

## Structure

```
cgc-3d/
  build.gradle                       Android library, no compose (builds under any consumer's AGP)
  src/main/assets/cgc/               merged into the consumer's assets → https://appassets.androidplatform.net/assets/cgc/
    cgc.js                           runtime: createViewer(container, config) / boot(config) → window.cgc
    cgc-anim.js                      declarative clips → AnimationClip, Mixamo-style retargeting, VRM bone map
    clips.json                       idle, walk, wave, dance (authored here, CC0-1.0)
    vendor/                          three.js r185 examples (MIT) + @pixiv/three-vrm 3.5.5 (MIT), imports flattened
  src/main/java/com/diegonmarcos/superapp/cgc/
    CgcSpec.kt                       the editor's declaration (scene, engine, categories, defaults, ai, ui)
    CgcConfig.kt                     configuration (one option per category) → scene ops; load/save/set; "coming soon"
    AvatarEngine.kt                  AvatarEngine, ModelFileEngine (shipping), VrmModularEngine (next), AvatarEngines
    OpenRouterAvatar.kt              "describe your avatar": request, reply → configuration, HttpPort, KeySource
  src/test/kotlin/…                  JVM suite (run by the consumer's unit task; mutants in cg-omniverse-avatar-proof.yml)
  tools/fbx-to-glb.sh, opt-glb.mjs   offline FBX → optimised GLB (FBX2glTF + meshopt/quantisation + ImageMagick)
  tools/metaperson-opt.json          the conversion rules used for the owner's MetaPerson export
```

three.js itself is not duplicated: a consumer's page maps `"three"` to Cloud Nav's copy (`ac_cloud-nav/app/src/main/assets/space`:
`three.module.min.js` + `three.core.min.js` + `OrbitControls.js`, copied in by the consumer's gradle as `assets/space/`).

## Runtime API (cgc.js)

Host page: an import map for `three`, a `#cgc` element, `boot(config)`. With an Android host, `window.CgcHost` (a
`@JavascriptInterface`) supplies `config()` synchronously, receives `event(json)` (`ready`, `loaded` {meshes, materials, morphs,
clips, vrm}, `clip`, `camera`, `saved`, `error`) and the export stream (`saveBegin(name, mime)`, `saveChunk(base64)`, `saveEnd()`).

| call | does |
|---|---|
| `load(url, kind?)` | GLB / glTF / VRM (three-vrm plugin, VRM0 rotated) / FBX; meshopt-compressed GLB supported |
| `apply(ops)` | `{clip, colors:{target:hex\|null}, tints:{target:hex\|null}, visible:{target:bool}, morphs:{name:0..1}, autoRotate, camera:"reset", insets:{top,bottom}}` |
| `play(id)` | cross-fades to a clip (bundled, the model's own, or a retargeted Mixamo clip) |
| `addMixamoClip(id, url, hipsHeight)` | retargets a Mixamo FBX/GLB clip onto the model |
| `setMorph`, `setColor`, `setVisible` | blendshapes (and VRM expressions), recolour keeping shading / tint, show-hide by mesh, material or node |
| `exportGLB(name)`, `snapshotPNG(name)` | GLTFExporter (current colours, visibility, every clip) / the current view |

A *target* names a mesh, a material, or a node whose subtree is meant. A colour swap turns the material's texture into its
luminance once and tints it (folds kept); a tint multiplies the texture as is.

Scene config (`data/avatar.json::scene` in the consumer): `model {url, kind}`, `background`, `exposure`, `camera {fov,
minDistance, maxDistance}`, `ground {shadow}`, `clips {url, default}`, `insets {top, bottom}` (CSS px the model stays clear of).

## Declaration (CgcSpec)

`scene`, `engine` (an AvatarEngine id), `default` (category → option), `categories[]` (`id`, `label`, `title`, `icon`, `kind`,
`targets`, `soon`, `options[]` {`id`, `label`, `clip` | `color` | `visible` | `morphs`, `thumb`}), `ai` (OpenRouter), `ui` (strings).
Kinds wired: `clip`, `color`, `tint`, `visible`, `morph`. Any other kind (`shape`, `parts`, …), `soon: true`, or a kind the active
engine cannot draw → the category's cards show "coming soon" and `set` refuses them.

## Creation pipeline (open source)

1. **Your own model** (`ModelFileEngine`, shipping): GLB/glTF/VRM/FBX. A MetaPerson Creator export (FBX, rig + 66 ARKit/viseme
   blendshapes) is converted offline with `tools/fbx-to-glb.sh`: FBX2glTF (BSD-3) → dense GLB; `opt-glb.mjs` → quantised normals
   and skin weights, meshopt-compressed geometry and morphs (EXPONENTIAL filter), textures resized (colour 1K, normals 512) and
   recompressed, normal maps re-attached; every compressed view is verified through three.js's MeshoptDecoder. Owner's avatar:
   8.0 MB FBX → **3.18 MB GLB** (rig, 11 meshes, 66 blendshapes, 14 textures). Only the GLB is committed.
2. **VRM modular avatars** (`VrmModularEngine`, next): a CC0 VRM base body + modular VRM parts (VRoid Studio exports or CC0 parts:
   hair, outfits, accessories) bound to the base skeleton; body/head shape via the base's blendshapes. Lights up the `parts` and
   `shape` kinds (hair, body, head shape, body-type presets) when its parts library is bundled.
3. **Animations**: `clips.json` (CC0, authored here, character-frame deltas on Mixamo bone names, so they play on Mixamo,
   MetaPerson and VRM rigs); external Mixamo-style clips via `addMixamoClip` (retargeted by bone name, hips scaled).
4. **AI** (`OpenRouterAvatar`): free text → OpenRouter chat completions (endpoint, model and key slot declared in `ai`) with a
   system prompt listing only wired categories and their option ids → JSON → mapped onto declared options only (id, label,
   or nearest declared colour for a hex; prose, code fences and `config`/`options` wrappers tolerated; unknown and coming-soon
   categories ignored and reported). The key is the user's own, kept by the consumer in EncryptedSharedPreferences (FinTerm's
   Config ▸ Sources pattern) and read per request through `KeySource`; it is only ever sent in that request's `Authorization`
   header and never logged, returned or put into an error.

## Licences and sizes

| component | licence | bytes (raw) |
|---|---|---|
| three.js r185 examples (GLTFLoader, FBXLoader, GLTFExporter, SkeletonUtils, BufferGeometryUtils, NURBSCurve/Utils, RoomEnvironment) | MIT | 393 KB |
| fflate (bundled by three.js examples) | MIT | 89 KB |
| meshoptimizer decoder (three.js examples) | MIT | 29 KB |
| @pixiv/three-vrm 3.5.5 | MIT | 155 KB |
| cgc.js, cgc-anim.js, clips.json, Kotlin | repo licence; clips CC0-1.0 | ~30 KB |
| FBX2glTF (tool only, not shipped) | BSD-3-Clause | — |

About 700 KB of JavaScript, deflated in the APK to roughly a quarter. three.js itself (~750 KB) is Cloud Nav's existing copy.
Licence entries: `licenses/inventory.json`.
