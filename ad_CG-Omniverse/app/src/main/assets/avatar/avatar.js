// The Avatar scene: a stylised character built from primitives (no model file), lit and posed, driven by window.applyAvatar(cfg).
// cfg is one value per group of data/avatar.json, colours already resolved to hex by Avatar.kt. Touch: drag rotates, pinch zooms.
import * as THREE from 'three';
import { OrbitControls } from '../space/OrbitControls.js';

const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;
document.body.appendChild(renderer.domElement);

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(30, window.innerWidth / window.innerHeight, 0.05, 50);
camera.position.set(0.9, 1.45, 4.3);
const controls = new OrbitControls(camera, renderer.domElement);
controls.target.set(0, 1.0, 0);
controls.enablePan = false;
controls.enableDamping = true;
controls.dampingFactor = 0.08;
controls.minDistance = 1.4;
controls.maxDistance = 7;
controls.minPolarAngle = 0.35;
controls.maxPolarAngle = 1.62;
controls.update();

// light: a sky/ground fill, a warm key that casts the soft shadow, a cool rim from behind
scene.add(new THREE.HemisphereLight(0xc9d8ff, 0x2a2140, 1.25));
const key = new THREE.DirectionalLight(0xfff1e0, 2.6);
key.position.set(2.2, 4.0, 3.0);
key.castShadow = true;
key.shadow.mapSize.set(1024, 1024);
key.shadow.camera.left = -1.5; key.shadow.camera.right = 1.5; key.shadow.camera.top = 2.5; key.shadow.camera.bottom = -0.5;
key.shadow.radius = 6; key.shadow.bias = -0.0005;
scene.add(key);
const rim = new THREE.DirectionalLight(0x9a7dff, 2.2);
rim.position.set(-2.5, 2.4, -3.0);
scene.add(rim);
const rim2 = new THREE.DirectionalLight(0x4fd9ff, 1.1);
rim2.position.set(2.8, 1.2, -2.2);
scene.add(rim2);

// stage: a glowing disc and a shadow catcher
function glowTexture() {
  const c = document.createElement('canvas'); c.width = c.height = 256;
  const g = c.getContext('2d');
  const r = g.createRadialGradient(128, 128, 10, 128, 128, 128);
  r.addColorStop(0, 'rgba(140,150,255,0.55)'); r.addColorStop(0.55, 'rgba(90,80,220,0.22)'); r.addColorStop(1, 'rgba(40,30,120,0)');
  g.fillStyle = r; g.fillRect(0, 0, 256, 256);
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; return t;
}
const glow = new THREE.Mesh(new THREE.PlaneGeometry(3.2, 3.2), new THREE.MeshBasicMaterial({ map: glowTexture(), transparent: true, depthWrite: false }));
glow.rotation.x = -Math.PI / 2; glow.position.y = 0.001; scene.add(glow);
const ring = new THREE.Mesh(new THREE.RingGeometry(0.78, 0.8, 96), new THREE.MeshBasicMaterial({ color: 0x8f96ff, transparent: true, opacity: 0.45 }));
ring.rotation.x = -Math.PI / 2; ring.position.y = 0.003; scene.add(ring);
const catcher = new THREE.Mesh(new THREE.CircleGeometry(1.6, 64), new THREE.ShadowMaterial({ opacity: 0.38 }));
catcher.rotation.x = -Math.PI / 2; catcher.position.y = 0.002; catcher.receiveShadow = true; scene.add(catcher);

// a few slow motes for depth
const motes = new THREE.Points(
  (() => { const g = new THREE.BufferGeometry(); const p = []; for (let i = 0; i < 160; i++) { const a = Math.random() * 6.283, r = 1.4 + Math.random() * 2.6; p.push(Math.cos(a) * r, Math.random() * 3.2, Math.sin(a) * r - 0.6); } g.setAttribute('position', new THREE.Float32BufferAttribute(p, 3)); return g; })(),
  new THREE.PointsMaterial({ color: 0xaab4ff, size: 0.018, transparent: true, opacity: 0.7, depthWrite: false }));
scene.add(motes);

// ── the character ───────────────────────────────────────────────────────────────────────────────────────────────────────────
const mat = (color, rough = 0.6, metal = 0) => new THREE.MeshStandardMaterial({ color: new THREE.Color(color), roughness: rough, metalness: metal });
function mesh(geo, m, parent, x = 0, y = 0, z = 0) {
  const o = new THREE.Mesh(geo, m); o.position.set(x, y, z); o.castShadow = true; o.receiveShadow = true; parent.add(o); return o;
}
const capsule = (r, len) => new THREE.CapsuleGeometry(r, len, 8, 20);
const sphere = (r, w = 32, h = 24) => new THREE.SphereGeometry(r, w, h);
const joint = (parent, x, y, z) => { const g = new THREE.Group(); g.position.set(x, y, z); parent.add(g); return g; };
const shade = (hex, f) => { const c = new THREE.Color(hex); c.offsetHSL(0, 0, f); return c; };

const BODY = {
  slim:     { chest: 0.88, hips: 0.9, limb: 0.86, shoulder: 0.94 },
  regular:  { chest: 1.0, hips: 1.0, limb: 1.0, shoulder: 1.0 },
  athletic: { chest: 1.16, hips: 0.98, limb: 1.14, shoulder: 1.12 },
  curvy:    { chest: 1.02, hips: 1.2, limb: 1.04, shoulder: 0.98 },
};
const HEIGHT = { short: 0.93, medium: 1.0, tall: 1.07 };

let rig = null;

function build(cfg) {
  const b = BODY[cfg.body] || BODY.regular;
  const skin = mat(cfg.skin, 0.55);
  const hairM = mat(cfg.hair_color, 0.45);
  const top = mat(cfg.top_color, cfg.outfit === 'suit' ? 0.5 : 0.75);
  const bottom = mat(cfg.bottom_color, 0.8);
  const white = mat('#f3f4f7', 0.6);
  const sole = mat('#d9dbe2', 0.5);
  const dark = mat('#141418', 0.35);
  const longSleeve = cfg.outfit !== 'tee';

  const root = new THREE.Group();
  root.scale.setScalar(HEIGHT[cfg.height] || 1);
  const hips = joint(root, 0, 0.93, 0);
  mesh(capsule(0.135 * b.hips, 0.1), bottom, hips, 0, 0.02, 0).scale.set(1.25, 1, 0.85);

  // legs
  const legs = [];
  for (const s of [-1, 1]) {
    const hip = joint(hips, 0.095 * s * b.hips, -0.02, 0);
    mesh(capsule(0.072 * b.limb, 0.3), bottom, hip, 0, -0.2, 0);
    const knee = joint(hip, 0, -0.41, 0);
    mesh(capsule(0.06 * b.limb, 0.3), bottom, knee, 0, -0.19, 0);
    const ankle = joint(knee, 0, -0.42, 0);
    const shoe = mesh(capsule(0.06, 0.12), white, ankle, 0, -0.035, 0.05); shoe.rotation.x = Math.PI / 2; shoe.scale.set(1.12, 1, 0.85);
    const so = mesh(new THREE.BoxGeometry(0.135, 0.025, 0.26), sole, ankle, 0, -0.085, 0.05);
    so.geometry.translate(0, 0, 0);
    legs.push({ hip, knee });
  }

  // torso
  const spine = joint(hips, 0, 0.08, 0);
  const torso = mesh(capsule(0.15, 0.26), top, spine, 0, 0.2, 0);
  torso.scale.set(1.18 * b.chest, 1, 0.78 * (0.9 + 0.1 * b.chest));
  if (cfg.outfit === 'jacket' || cfg.outfit === 'suit') {
    // an open front: the shirt beneath, and lapels
    const shirt = mesh(new THREE.CapsuleGeometry(0.06, 0.2, 6, 12), cfg.outfit === 'suit' ? white : mat('#e9e6df', 0.8), spine, 0, 0.24, 0.103 * b.chest);
    shirt.scale.set(1, 1, 0.45);
    if (cfg.outfit === 'suit') { const tie = mesh(new THREE.BoxGeometry(0.035, 0.2, 0.012), mat('#b3263a', 0.45), spine, 0, 0.22, 0.13 * b.chest); tie.rotation.x = -0.12; }
    for (const s of [-1, 1]) { const lap = mesh(new THREE.BoxGeometry(0.05, 0.24, 0.02), mat(shade(cfg.top_color, -0.08), 0.6), spine, 0.06 * s, 0.26, 0.112 * b.chest); lap.rotation.z = 0.28 * s; lap.rotation.x = -0.18; }
    mesh(new THREE.TorusGeometry(0.075, 0.024, 10, 24), top, spine, 0, 0.42, 0).rotation.x = Math.PI / 2;
  } else if (cfg.outfit === 'hoodie') {
    const hood = mesh(new THREE.TorusGeometry(0.1, 0.045, 12, 28), top, spine, 0, 0.42, -0.03); hood.rotation.x = Math.PI / 2.4;
    const pocket = mesh(new THREE.BoxGeometry(0.2, 0.08, 0.02), mat(shade(cfg.top_color, -0.06), 0.8), spine, 0, 0.1, 0.12 * b.chest);
    pocket.rotation.x = 0.1;
    for (const s of [-1, 1]) mesh(new THREE.CylinderGeometry(0.006, 0.006, 0.12, 6), white, spine, 0.03 * s, 0.33, 0.125 * b.chest);
  } else if (cfg.outfit === 'tee') {
    mesh(new THREE.TorusGeometry(0.066, 0.014, 8, 24), mat(shade(cfg.top_color, -0.1), 0.7), spine, 0, 0.42, 0.005).rotation.x = Math.PI / 2;
  }
  // belt
  mesh(new THREE.CylinderGeometry(0.155 * b.hips * 1.2, 0.155 * b.hips * 1.2, 0.035, 28), dark, hips, 0, 0.085, 0).scale.set(1, 1, 0.72);

  // arms
  const arms = [];
  for (const s of [-1, 1]) {
    const sh = joint(spine, 0.205 * s * b.shoulder, 0.37, 0);
    mesh(sphere(0.07 * b.limb), top, sh, 0, 0, 0);
    mesh(capsule(0.056 * b.limb, 0.22), longSleeve ? top : top, sh, 0, -0.14, 0).scale.set(1, 1, 0.95);
    const el = joint(sh, 0, -0.29, 0);
    mesh(capsule(0.047 * b.limb, 0.2), longSleeve ? top : skin, el, 0, -0.13, 0);
    if (longSleeve) mesh(new THREE.CylinderGeometry(0.05 * b.limb, 0.05 * b.limb, 0.03, 16), mat(shade(cfg.top_color, -0.1), 0.7), el, 0, -0.25, 0);
    const wr = joint(el, 0, -0.28, 0);
    const hand = mesh(sphere(0.05), skin, wr, 0, -0.03, 0); hand.scale.set(0.85, 1.15, 0.7);
    mesh(capsule(0.017, 0.035), skin, wr, -0.035 * s, -0.005, 0.02).rotation.z = 0.6 * s;
    arms.push({ sh, el, side: s });
  }

  // neck and head
  const neckJ = joint(spine, 0, 0.44, 0);
  mesh(new THREE.CylinderGeometry(0.048, 0.055, 0.12, 16), skin, neckJ, 0, 0.03, 0);
  const head = joint(neckJ, 0, 0.12, 0);
  const skull = mesh(sphere(0.165, 48, 36), skin, head, 0, 0.1, 0); skull.scale.set(0.93, 1.08, 0.98);
  const jaw = mesh(sphere(0.12, 32, 20), skin, head, 0, 0.03, 0.03); jaw.scale.set(0.95, 0.85, 0.95);
  for (const s of [-1, 1]) { const ear = mesh(sphere(0.035, 16, 12), skin, head, 0.152 * s, 0.09, 0); ear.scale.set(0.5, 1, 0.75); }
  const nose = mesh(sphere(0.022, 16, 12), mat(shade(cfg.skin, -0.04), 0.5), head, 0, 0.075, 0.16); nose.scale.set(1, 1.1, 1);
  const eyes = [];
  for (const s of [-1, 1]) {
    const e = joint(head, 0.058 * s, 0.115, 0.135);
    const white_ = mesh(sphere(0.026, 20, 16), mat('#fbfbfd', 0.2), e, 0, 0, 0); white_.scale.set(1, 1.05, 0.6);
    mesh(sphere(0.017, 20, 16), mat('#2a1d18', 0.15), e, 0, 0, 0.012).scale.set(1, 1.05, 0.6);
    mesh(sphere(0.005, 8, 6), mat('#ffffff', 0.1), e, 0.006, 0.008, 0.022);
    const brow = mesh(capsule(0.007, 0.04), hairM, head, 0.06 * s, 0.163, 0.145); brow.rotation.z = Math.PI / 2 + 0.12 * s;
    const cheek = mesh(sphere(0.022, 12, 8), new THREE.MeshStandardMaterial({ color: '#ff8a8a', transparent: true, opacity: 0.22, roughness: 0.8 }), head, 0.085 * s, 0.05, 0.125);
    cheek.castShadow = false; cheek.scale.set(1, 0.6, 0.3);
    eyes.push(e);
  }
  const smile = mesh(new THREE.TorusGeometry(0.03, 0.0065, 8, 24, Math.PI * 0.9), mat('#9c3d4a', 0.4), head, 0, 0.035, 0.14);
  smile.rotation.z = Math.PI + Math.PI * 0.05;

  // hair
  const cap = (r, thetaLen, y = 0.1, z = -0.005) => { const h = mesh(new THREE.SphereGeometry(r, 48, 32, 0, Math.PI * 2, 0, thetaLen), hairM, head, 0, y, z); h.scale.set(0.95, 1.08, 1.0); return h; };
  switch (cfg.hair) {
    case 'buzz': cap(0.17, 1.35).rotation.x = -0.35; break;
    case 'short': cap(0.176, 1.5).rotation.x = -0.4; { const f = mesh(sphere(0.07, 20, 14), hairM, head, 0.04, 0.215, 0.11); f.scale.set(1.5, 0.45, 0.8); } break;
    case 'quiff': {
      cap(0.176, 1.45).rotation.x = -0.45;
      const q = mesh(sphere(0.09, 28, 20), hairM, head, 0.01, 0.255, 0.075); q.scale.set(1.35, 0.7, 1.1); q.rotation.x = -0.5;
      const q2 = mesh(sphere(0.07, 24, 16), hairM, head, -0.02, 0.27, 0.0); q2.scale.set(1.5, 0.6, 1.4);
      for (const s of [-1, 1]) { const sd = mesh(sphere(0.06, 16, 12), hairM, head, 0.135 * s, 0.14, -0.03); sd.scale.set(0.45, 0.9, 1.1); }
      break;
    }
    case 'long': {
      cap(0.18, 1.62).rotation.x = -0.25;
      const back = mesh(capsule(0.14, 0.2), hairM, head, 0, -0.02, -0.07); back.scale.set(1.15, 1, 0.6);
      for (const s of [-1, 1]) { const l = mesh(capsule(0.05, 0.22), hairM, head, 0.14 * s, 0.0, 0.02); l.rotation.z = 0.08 * s; }
      break;
    }
    case 'bun': cap(0.175, 1.55).rotation.x = -0.3; mesh(sphere(0.075, 24, 18), hairM, head, 0, 0.28, -0.1); break;
    case 'curly': {
      cap(0.17, 1.5).rotation.x = -0.35;
      const n = 46;
      for (let i = 0; i < n; i++) {
        const phi = Math.acos(1 - (i + 0.5) / n * 1.15), th = i * 2.39996;
        const v = new THREE.Vector3(Math.sin(phi) * Math.cos(th), Math.cos(phi), Math.sin(phi) * Math.sin(th));
        if (v.z > 0.55 && v.y < 0.7) continue;
        mesh(sphere(0.048, 12, 10), hairM, head, v.x * 0.17, 0.1 + v.y * 0.185, v.z * 0.17 - 0.01);
      }
      break;
    }
  }

  // accessories
  switch (cfg.accessory) {
    case 'glasses': {
      const fm = mat('#15161b', 0.3, 0.4);
      for (const s of [-1, 1]) { mesh(new THREE.TorusGeometry(0.036, 0.006, 8, 28), fm, head, 0.058 * s, 0.115, 0.158); const arm = mesh(new THREE.BoxGeometry(0.006, 0.006, 0.15), fm, head, 0.098 * s, 0.12, 0.08); arm.rotation.y = 0.12 * s; }
      mesh(new THREE.BoxGeometry(0.03, 0.006, 0.006), fm, head, 0, 0.12, 0.16);
      for (const s of [-1, 1]) mesh(new THREE.CircleGeometry(0.034, 24), new THREE.MeshStandardMaterial({ color: '#9fd0ff', transparent: true, opacity: 0.18, roughness: 0.05, metalness: 0.4 }), head, 0.058 * s, 0.115, 0.157);
      break;
    }
    case 'headphones': {
      const hm = mat('#1d1f27', 0.35, 0.3), acc = mat('#7c83ff', 0.3, 0.2);
      const band = mesh(new THREE.TorusGeometry(0.19, 0.016, 10, 40, Math.PI), hm, head, 0, 0.1, -0.01); band.scale.set(0.95, 1.08, 1);
      for (const s of [-1, 1]) {
        const cup = mesh(new THREE.CylinderGeometry(0.06, 0.06, 0.045, 28), hm, head, 0.175 * s, 0.08, 0); cup.rotation.z = Math.PI / 2;
        const ringA = mesh(new THREE.TorusGeometry(0.045, 0.008, 8, 28), acc, head, 0.2 * s, 0.08, 0); ringA.rotation.y = Math.PI / 2;
      }
      break;
    }
    case 'cap': {
      const cm = mat(cfg.top_color, 0.7);
      const dome = mesh(new THREE.SphereGeometry(0.182, 40, 24, 0, Math.PI * 2, 0, Math.PI / 2), cm, head, 0, 0.17, 0); dome.scale.set(0.98, 0.78, 1.02);
      const brim = mesh(new THREE.CylinderGeometry(0.12, 0.12, 0.012, 32, 1, false, -Math.PI / 2, Math.PI), cm, head, 0, 0.18, 0.12); brim.scale.set(1.15, 1, 1.0);
      mesh(sphere(0.014, 10, 8), mat('#ffffff', 0.5), head, 0, 0.31, 0);
      break;
    }
    case 'visor': {
      const vm = mat('#f2f3f7', 0.35), glass = new THREE.MeshStandardMaterial({ color: '#141a3a', roughness: 0.08, metalness: 0.6, emissive: '#3b47ff', emissiveIntensity: 0.35 });
      const shell = mesh(new THREE.BoxGeometry(0.27, 0.11, 0.1, 4, 4, 4), vm, head, 0, 0.12, 0.13);
      shell.geometry.attributes.position && roundBox(shell.geometry, 0.035);
      const face = mesh(new THREE.BoxGeometry(0.24, 0.085, 0.02), glass, head, 0, 0.12, 0.185); roundBox(face.geometry, 0.02);
      const strap = mesh(new THREE.TorusGeometry(0.175, 0.014, 8, 40), mat('#2a2d38', 0.6), head, 0, 0.12, 0); strap.rotation.x = Math.PI / 2; strap.scale.set(0.95, 1.05, 1);
      break;
    }
  }

  root.traverse(o => { if (o.isMesh && o.material.transparent) o.castShadow = false; });
  return { root, hips, spine, head, neckJ, arms, legs, eyes };
}

// soften a box's corners by pushing its vertices onto a rounded shape
function roundBox(geo, r) {
  geo.computeBoundingBox();
  const bb = geo.boundingBox, half = new THREE.Vector3(), p = new THREE.Vector3(), c = new THREE.Vector3();
  bb.getSize(half).multiplyScalar(0.5);
  const inner = half.clone().subScalar(r).max(new THREE.Vector3(0, 0, 0));
  const pos = geo.attributes.position;
  for (let i = 0; i < pos.count; i++) {
    p.fromBufferAttribute(pos, i);
    c.copy(p).clamp(inner.clone().negate(), inner);
    const d = p.clone().sub(c);
    if (d.lengthSq() > 0) p.copy(c).add(d.normalize().multiplyScalar(r));
    pos.setXYZ(i, p.x, p.y, p.z);
  }
  geo.computeVertexNormals();
}

function dispose(o) { o.traverse(n => { if (n.isMesh) { n.geometry.dispose(); n.material.dispose(); } }); }

let pose = 'idle';
window.applyAvatar = function (cfg) {
  if (typeof cfg === 'string') cfg = JSON.parse(cfg);
  if (rig) { scene.remove(rig.root); dispose(rig.root); }
  rig = build(cfg);
  pose = cfg.pose || 'idle';
  scene.add(rig.root);
  return true;
};

// ── poses ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────
function animate(t) {
  if (!rig) return;
  const { hips, spine, head, arms, legs, eyes, root } = rig;
  const breathe = Math.sin(t * 1.6);
  hips.position.y = 0.93; hips.rotation.set(0, 0, 0); spine.rotation.set(0, 0, 0); head.rotation.set(0, 0, 0); root.rotation.y = 0;
  spine.scale.set(1, 1 + breathe * 0.008, 1);
  for (const l of legs) { l.hip.rotation.set(0, 0, 0); l.knee.rotation.set(0, 0, 0); }
  for (const a of arms) { a.sh.rotation.set(0, 0, 0.12 * a.side + breathe * 0.015 * a.side); a.el.rotation.set(-0.12, 0, 0); }
  head.rotation.y = Math.sin(t * 0.45) * 0.18; head.rotation.x = Math.sin(t * 0.7) * 0.03;
  switch (pose) {
    case 'wave': {
      const r = arms.find(a => a.side < 0) || arms[0];
      r.sh.rotation.set(0, 0, -2.55); r.el.rotation.set(0, 0, -0.35 + Math.sin(t * 7) * 0.45);
      head.rotation.z = 0.08; spine.rotation.z = 0.04;
      break;
    }
    case 'dance': {
      const b = Math.abs(Math.sin(t * 3.2));
      hips.position.y = 0.93 - b * 0.05; hips.rotation.y = Math.sin(t * 3.2) * 0.25; spine.rotation.z = Math.sin(t * 3.2) * 0.08;
      for (const a of arms) { const ph = a.side > 0 ? 0 : Math.PI; a.sh.rotation.set(Math.sin(t * 3.2 + ph) * 0.9, 0, (0.5 + Math.sin(t * 6.4) * 0.3) * a.side); a.el.rotation.set(-1.3, 0, 0); }
      for (const l of legs) { l.hip.rotation.x = -b * 0.25; l.knee.rotation.x = b * 0.5; }
      head.rotation.z = Math.sin(t * 3.2) * 0.12;
      break;
    }
    case 'hero': {
      for (const a of arms) { a.sh.rotation.set(0.15, 0, 0.75 * a.side); a.el.rotation.set(-0.2, 0, -1.9 * a.side); }
      for (const l of legs) l.hip.rotation.z = 0.09 * Math.sign(l.hip.position.x);
      head.rotation.x = -0.08; spine.scale.set(1.02, 1 + breathe * 0.008, 1.02);
      break;
    }
    default: root.rotation.y = Math.sin(t * 0.3) * 0.08;
  }
  // blink every few seconds
  const bl = (t % 4.2) < 0.12 ? 0.12 : 1;
  for (const e of eyes) e.scale.y = bl;
}

const clock = new THREE.Clock();
renderer.setAnimationLoop(() => {
  const t = clock.getElapsedTime();
  animate(t);
  motes.rotation.y = t * 0.02;
  ring.material.opacity = 0.35 + Math.sin(t * 1.4) * 0.12;
  controls.update();
  renderer.render(scene, camera);
});

window.addEventListener('resize', () => {
  camera.aspect = window.innerWidth / window.innerHeight; camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});

// a complete default avatar even before the app pushes the saved one
window.applyAvatar({ body: 'regular', height: 'medium', skin: '#d9a27a', hair: 'quiff', hair_color: '#3b2418', outfit: 'jacket',
  top_color: '#4b4fd8', bottom_color: '#30323a', accessory: 'headphones', pose: 'idle' });
window.avatarReady = true;
