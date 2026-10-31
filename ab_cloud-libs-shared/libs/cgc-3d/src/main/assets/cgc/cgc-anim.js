// cgc-3d animation: declarative humanoid clips (clips.json) built for ANY rig that names its bones the Mixamo way (Hips, Spine,
// LeftArm ...; a "mixamorig" prefix is ignored) or is a VRM humanoid (its bones are found through the VRM bone map below), and
// retargeting of Mixamo-style clips (an FBX/GLB clip whose tracks name mixamorig bones) onto such a rig.
//
// A clip declares, per bone, a rotation in the CHARACTER frame (+X the character's left, +Y up, +Z forward; Euler degrees in
// order XYZ, so Z is applied first) as base + amp * shape(2*pi*(freq*t/duration + phase)), shape = sine | half (max(0, sin)) | abs;
// a list of such terms is summed.
// The rotation is relative to the rig's REST pose (a T-pose for Mixamo, MetaPerson and VRM), and is turned into the bone's local
// quaternion as P^-1 * q * P * L (P the parent's rest world rotation, L the bone's rest local rotation): a delta therefore means
// the same motion on every rig, whatever its bones' local axes. Pure three.js, no DOM, so Node can run it for previews.

/** VRM humanoid bone name -> the Mixamo name the clips use. */
export const VRM_TO_MIXAMO = {
  hips: 'Hips', spine: 'Spine', chest: 'Spine1', upperChest: 'Spine2', neck: 'Neck', head: 'Head',
  leftShoulder: 'LeftShoulder', leftUpperArm: 'LeftArm', leftLowerArm: 'LeftForeArm', leftHand: 'LeftHand',
  rightShoulder: 'RightShoulder', rightUpperArm: 'RightArm', rightLowerArm: 'RightForeArm', rightHand: 'RightHand',
  leftUpperLeg: 'LeftUpLeg', leftLowerLeg: 'LeftLeg', leftFoot: 'LeftFoot', leftToes: 'LeftToeBase',
  rightUpperLeg: 'RightUpLeg', rightLowerLeg: 'RightLeg', rightFoot: 'RightFoot', rightToes: 'RightToeBase',
};

const strip = (n) => String(n).replace(/^mixamorig[:_]?/i, '');

/** name (Mixamo) -> bone node of [root]; [vrm] (a @pixiv/three-vrm VRM) wins when given. */
export function boneMap(root, vrm) {
  const map = new Map();
  root.traverse((o) => { if (o.isBone || o.type === 'Bone' || o.isObject3D) { const k = strip(o.name); if (k && !map.has(k)) map.set(k, o); } });
  if (vrm && vrm.humanoid) {
    for (const [v, m] of Object.entries(VRM_TO_MIXAMO)) {
      const n = vrm.humanoid.getRawBoneNode ? vrm.humanoid.getRawBoneNode(v) : null;
      if (n) map.set(m, n);
    }
  }
  return map;
}

function shape(kind, x) {
  const s = Math.sin(x);
  if (kind === 'half') return Math.max(0, s);
  if (kind === 'abs') return Math.abs(s);
  return s;
}

function value(spec, t, dur) {
  if (Array.isArray(spec)) return spec.map((s) => value(s, t, dur)).reduce((a, b) => a.map((x, i) => x + b[i]), [0, 0, 0]);
  const base = spec.base || [0, 0, 0], amp = spec.amp || [0, 0, 0];
  const x = 2 * Math.PI * ((spec.freq ?? 1) * (t / dur) + (spec.phase || 0));
  const k = shape(spec.shape, x);
  return [0, 1, 2].map((i) => base[i] + amp[i] * k);
}

/**
 * Builds a THREE.AnimationClip from one declared clip [def] for the rig under [root] (call with the rig in its rest pose).
 * Bones the rig lacks are skipped, so a partial rig still plays the rest.
 */
export function buildClip(THREE, root, def, vrm) {
  root.updateMatrixWorld(true);
  const bones = boneMap(root, vrm);
  const rootQ = root.getWorldQuaternion(new THREE.Quaternion());
  const rootQi = rootQ.clone().invert();
  const dur = def.duration || 1;
  const steps = Math.max(24, Math.ceil(dur * 24));
  const times = Array.from({ length: steps + 1 }, (_, i) => (i * dur) / steps);
  const tracks = [];
  const e = new THREE.Euler(), q = new THREE.Quaternion(), d2r = Math.PI / 180;
  for (const [name, spec] of Object.entries(def.bones || {})) {
    const bone = bones.get(name);
    if (!bone) continue;
    // parent's rest world rotation, in the character frame
    const P = bone.parent ? rootQi.clone().multiply(bone.parent.getWorldQuaternion(new THREE.Quaternion())) : new THREE.Quaternion();
    const Pi = P.clone().invert();
    const L = bone.quaternion.clone();
    const values = [];
    for (const t of times) {
      const [x, y, z] = value(spec, t, dur);
      e.set(x * d2r, y * d2r, z * d2r, 'XYZ');
      q.setFromEuler(e);
      const out = Pi.clone().multiply(q).multiply(P).multiply(L);
      values.push(out.x, out.y, out.z, out.w);
    }
    tracks.push(new THREE.QuaternionKeyframeTrack(`${bone.uuid}.quaternion`, times, values));
  }
  if (def.hips && bones.get('Hips')) {
    const hips = bones.get('Hips');
    const rest = hips.getWorldPosition(new THREE.Vector3());
    const values = [];
    for (const t of times) {
      const [x, y, z] = value(def.hips, t, dur);
      const w = new THREE.Vector3(x, y, z).applyQuaternion(rootQ).add(rest);
      const local = hips.parent ? hips.parent.worldToLocal(w.clone()) : w;
      values.push(local.x, local.y, local.z);
    }
    tracks.push(new THREE.VectorKeyframeTrack(`${hips.uuid}.position`, times, values));
  }
  return new THREE.AnimationClip(def.id || 'clip', dur, tracks);
}

/**
 * Retargets a Mixamo-style [clip] (tracks named mixamorig:Bone.quaternion / .position) onto the rig under [root]: tracks are
 * renamed to the target's bones (same Mixamo names, or the VRM map), unknown bones dropped, and the hips translation scaled by the
 * ratio of the two rest hips heights ([sourceHipsHeight] in the source's own units, against the target hips' local rest height) so a taller or shorter rig does not float or sink. Both rigs are
 * expected in a T-pose rest (Mixamo, MetaPerson and VRM are).
 */
export function retargetMixamo(THREE, clip, root, sourceHipsHeight, vrm) {
  root.updateMatrixWorld(true);
  const bones = boneMap(root, vrm);
  const hips = bones.get('Hips');
  const targetH = hips ? hips.position.y : 1;
  const k = sourceHipsHeight > 0 && targetH > 0 ? targetH / sourceHipsHeight : 1;
  const tracks = [];
  for (const tr of clip.tracks) {
    const dot = tr.name.lastIndexOf('.');
    const bone = bones.get(strip(tr.name.slice(0, dot)));
    const prop = tr.name.slice(dot + 1);
    if (!bone) continue;
    if (prop === 'quaternion') tracks.push(new THREE.QuaternionKeyframeTrack(`${bone.uuid}.quaternion`, tr.times, tr.values));
    else if (prop === 'position' && bone === hips) {
      // source hips positions are in the source's units: scaled into the target hips' local units
      const v = new Float32Array(tr.values.length);
      for (let i = 0; i < v.length; i++) v[i] = tr.values[i] * k;
      tracks.push(new THREE.VectorKeyframeTrack(`${bone.uuid}.position`, tr.times, v));
    }
  }
  return new THREE.AnimationClip(clip.name, clip.duration, tracks);
}
