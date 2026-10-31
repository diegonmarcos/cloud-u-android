#!/bin/sh
# cgc-3d offline conversion: an FBX avatar (e.g. a MetaPerson Creator export) -> an optimised, rigged GLB, open-source tools only.
#   1. FBX2glTF (godotengine fork, BSD-3-Clause, the standalone Linux binary of its v0.13.1 release): FBX -> GLB, dense blend
#      shapes, 4 skin weights per vertex. Rig, skins and the 66 ARKit/viseme blendshapes are kept.
#   2. opt-glb.mjs (this folder, plain Node, no npm package): KHR_mesh_quantization (int8 normals, uint8 joints/weights) +
#      EXT_meshopt_compression (meshopt vertex codec v0; EXPONENTIAL filter on positions, UVs and morph deltas), textures
#      resized/re-encoded with ImageMagick and the normal maps FBX2glTF leaves unwired re-attached per rules.json; every
#      compressed view is round-tripped through three.js's MeshoptDecoder before the file is written.
# usage: tools/fbx-to-glb.sh model.fbx out.glb rules.json FBX2glTF-binary meshopt_decoder.module.js
# Run it offline or in a CI step; commit only the optimised GLB (the FBX stays out of git).
set -eu
IN=$1; OUT=$2; RULES=$3; FBX2GLTF=$4; DECODER=$5
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
"$FBX2GLTF" --binary --blend-shape-no-sparse --skinning-weights 4 --input "$IN" --output "$TMP/dense"
node "$(dirname "$0")/opt-glb.mjs" "$TMP/dense.glb" "$OUT" "$RULES" "$DECODER"
ls -l "$OUT"
