// cgc-3d tools/opt-glb.mjs: FBX2glTF GLB -> quantised (KHR_mesh_quantization) + EXT_meshopt_compression GLB, textures via ImageMagick.
// usage: node opt-glb.mjs in.glb out.glb rules.json meshopt_decoder.module.js   (called by fbx-to-glb.sh; plain Node, no npm)
import fs from 'fs';
import { execFileSync } from 'child_process';
import os from 'os';
import path from 'path';

const [, , IN, OUT, RULES, DECODER] = process.argv;
const rules = JSON.parse(fs.readFileSync(RULES, 'utf8'));
const d = fs.readFileSync(IN);
const jl = d.readUInt32LE(12);
const j = JSON.parse(d.subarray(20, 20 + jl).toString('utf8'));
const bin = d.subarray(20 + jl + 8);
const NC = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4, MAT4: 16 };
const CT = { 5120: Int8Array, 5121: Uint8Array, 5122: Int16Array, 5123: Uint16Array, 5125: Uint32Array, 5126: Float32Array };

function read(ai) {
  const A = j.accessors[ai], bv = j.bufferViews[A.bufferView], T = CT[A.componentType], n = NC[A.type];
  const stride = bv.byteStride || n * T.BYTES_PER_ELEMENT;
  const out = new Float64Array(A.count * n);
  const dv = new DataView(bin.buffer, bin.byteOffset + (bv.byteOffset || 0) + (A.byteOffset || 0));
  for (let i = 0; i < A.count; i++) for (let c = 0; c < n; c++) {
    const o = i * stride + c * T.BYTES_PER_ELEMENT;
    out[i * n + c] = T === Float32Array ? dv.getFloat32(o, true) : T === Uint16Array ? dv.getUint16(o, true) : T === Uint32Array ? dv.getUint32(o, true) : dv.getUint8(o);
  }
  return { A, n, v: out };
}

// ---------- meshopt vertex codec v0 (ATTRIBUTES mode) ----------
function blockSize(vs) { const r = Math.floor(8192 / vs) & ~15; return r < 256 ? r : 256; }
const zig = (v) => (((v << 1) & 0xff) ^ (v & 0x80 ? 0xff : 0));
function groupSize(g, bits) {
  if (bits === 0) { for (const x of g) if (x) return Infinity; return 0; }
  if (bits === 8) return 16;
  const s = (1 << bits) - 1; let e = 0; for (const x of g) if (x >= s) e++;
  return (16 * bits) / 8 + e;
}
function encodeBytes(out, buf) {
  const groups = buf.length / 16, hdr = Math.floor((groups + 3) / 4), h = out.length;
  for (let i = 0; i < hdr; i++) out.push(0);
  for (let gi = 0; gi < groups; gi++) {
    const g = buf.subarray(gi * 16, gi * 16 + 16);
    let best = 8, bs = 16;
    for (const b of [0, 2, 4]) { const s = groupSize(g, b); if (s < bs) { bs = s; best = b; } }
    const code = { 0: 0, 2: 1, 4: 2, 8: 3 }[best];
    out[h + (gi >> 2)] |= code << ((gi & 3) * 2);
    if (best === 0) continue;
    if (best === 8) { for (const x of g) out.push(x); continue; }
    const per = 8 / best, s = (1 << best) - 1;
    for (let i = 0; i < 16; i += per) { let byte = 0; for (let k = 0; k < per; k++) { byte = (byte << best) | (g[i + k] >= s ? s : g[i + k]); } out.push(byte & 0xff); }
    for (const x of g) if (x >= s) out.push(x);
  }
}
function encodeVertexBuffer(data, count, vs) {
  const out = [0xa0];
  const last = data.slice(0, vs);
  const bsz = blockSize(vs);
  for (let off = 0; off < count; off += bsz) {
    const n = Math.min(bsz, count - off), na = (n + 15) & ~15;
    const buf = new Uint8Array(na);
    for (let k = 0; k < vs; k++) {
      let p = last[k];
      buf.fill(0);
      for (let i = 0; i < n; i++) { const v = data[(off + i) * vs + k]; buf[i] = zig((v - p) & 0xff); p = v; }
      encodeBytes(out, buf);
    }
    last.set(data.subarray((off + n - 1) * vs, (off + n) * vs));
  }
  const tail = Math.max(vs, 32);
  for (let i = 0; i < tail - vs; i++) out.push(0);
  for (let i = 0; i < vs; i++) out.push(data[i]);
  return Uint8Array.from(out);
}

// ---------- rebuild ----------
const comp = []; // compressed chunks (buffer 0)
const raw = []; // uncompressed chunks (buffer 0 as well)
let fallbackLen = 0;
const views = [];
const accessors = [];
const checks = [];
function pad4(n) { return (n + 3) & ~3; }
function expFilter(floats, n, bits) { // EXPONENTIAL filter, exponent shared per vertex: int32 = (exp << 24) | (mantissa & 0xffffff)
  const out = new Uint8Array(floats.length * 4); const dv = new DataView(out.buffer);
  for (let i = 0; i < floats.length / n; i++) {
    let mx = 0; for (let c = 0; c < n; c++) mx = Math.max(mx, Math.abs(floats[i * n + c]));
    let e = mx > 0 ? Math.ceil(Math.log2(mx + 1e-300)) - (bits - 1) : 0;
    e = Math.max(-100, Math.min(100, e));
    for (let c = 0; c < n; c++) {
      let m = Math.round(floats[i * n + c] / 2 ** e);
      m = Math.max(-(2 ** 23 - 1), Math.min(2 ** 23 - 1, m));
      dv.setInt32((i * n + c) * 4, ((e & 0xff) << 24) | (m & 0xffffff), true);
    }
  }
  return out;
}
function addCompressed(bytes, stride, count, filter, floats) {
  const enc = encodeVertexBuffer(bytes, count, stride);
  checks.push({ bytes, enc, count, stride, filter, floats });
  const bv = { buffer: 1, byteOffset: fallbackLen, byteLength: bytes.length, byteStride: stride,
    extensions: { EXT_meshopt_compression: { buffer: 0, byteOffset: -1, byteLength: enc.length, byteStride: stride, mode: 'ATTRIBUTES', count, ...(filter ? { filter } : {}) } } };
  fallbackLen += pad4(bytes.length);
  comp.push({ bv, enc });
  views.push(bv); return views.length - 1;
}
function addRaw(bytes, extra = {}) { const bv = { buffer: 0, byteOffset: -1, byteLength: bytes.length, ...extra }; raw.push({ bv, bytes }); views.push(bv); return views.length - 1; }
function acc(o) { accessors.push(o); return accessors.length - 1; }

function packFloat(v, n) { // stride n*4
  const b = new Uint8Array(v.length * 4); const dv = new DataView(b.buffer); v.forEach((x, i) => dv.setFloat32(i * 4, x, true)); return b;
}
function attrFloat(r, keepMinMax, bits) {
  const view = bits ? addCompressed(expFilter(r.v, r.n, bits), r.n * 4, r.A.count, 'EXPONENTIAL', r.v) : addCompressed(packFloat(r.v, r.n), r.n * 4, r.A.count);
  const a = { bufferView: view, componentType: 5126, count: r.A.count, type: r.A.type };
  if (keepMinMax && r.A.min) { a.min = r.A.min; a.max = r.A.max; }
  return acc(a);
}
function attrNormal(r) { // int8 normalised, stride 4
  const b = new Uint8Array(r.A.count * 4); const dv = new DataView(b.buffer);
  for (let i = 0; i < r.A.count; i++) { let x = r.v[i * 3], y = r.v[i * 3 + 1], z = r.v[i * 3 + 2]; const L = Math.hypot(x, y, z) || 1;
    dv.setInt8(i * 4, Math.round((x / L) * 127)); dv.setInt8(i * 4 + 1, Math.round((y / L) * 127)); dv.setInt8(i * 4 + 2, Math.round((z / L) * 127)); }
  return acc({ bufferView: addCompressed(b, 4, r.A.count), componentType: 5120, normalized: true, count: r.A.count, type: 'VEC3' });
}
function attrJoints(r) {
  const b = new Uint8Array(r.A.count * 4); for (let i = 0; i < b.length; i++) { if (r.v[i] > 255) throw new Error('joint > 255'); b[i] = r.v[i]; }
  return acc({ bufferView: addCompressed(b, 4, r.A.count), componentType: 5121, count: r.A.count, type: 'VEC4' });
}
function attrWeights(r) {
  const b = new Uint8Array(r.A.count * 4);
  for (let i = 0; i < r.A.count; i++) {
    const w = [0, 1, 2, 3].map((k) => r.v[i * 4 + k]); const s = w.reduce((a, c) => a + c, 0) || 1;
    const q = w.map((x) => Math.round((x / s) * 255)); let diff = 255 - q.reduce((a, c) => a + c, 0);
    const mi = q.indexOf(Math.max(...q)); q[mi] += diff; q.forEach((x, k) => (b[i * 4 + k] = x));
  }
  return acc({ bufferView: addCompressed(b, 4, r.A.count), componentType: 5121, normalized: true, count: r.A.count, type: 'VEC4' });
}
function indices(r) { // uint32, ATTRIBUTES stride 4
  const b = new Uint8Array(r.A.count * 4); const dv = new DataView(b.buffer); r.v.forEach((x, i) => dv.setUint32(i * 4, x, true));
  return acc({ bufferView: addCompressed(b, 4, r.A.count), componentType: 5125, count: r.A.count, type: 'SCALAR' });
}

for (const m of j.meshes) for (const p of m.primitives) {
  const at = {};
  for (const [k, ai] of Object.entries(p.attributes)) {
    const r = read(ai);
    if (k === 'NORMAL') at[k] = attrNormal(r);
    else if (k === 'JOINTS_0') at[k] = attrJoints(r);
    else if (k === 'WEIGHTS_0') at[k] = attrWeights(r);
    else if (k === 'POSITION' || k.startsWith('TEXCOORD')) at[k] = attrFloat(r, k === 'POSITION', k === 'POSITION' ? rules.bits.position : rules.bits.uv);
    // anything else (tangents, colours) is dropped
  }
  p.attributes = at;
  p.indices = indices(read(p.indices));
  if (p.targets) p.targets = p.targets.map((t) => { const o = {}; if (t.POSITION !== undefined) o.POSITION = attrFloat(read(t.POSITION), true, rules.bits.morph); return o; });
}
for (const s of j.skins || []) if (s.inverseBindMatrices !== undefined) {
  const r = read(s.inverseBindMatrices);
  s.inverseBindMatrices = acc({ bufferView: addRaw(packFloat(r.v, 16)), componentType: 5126, count: r.A.count, type: 'MAT4' });
}

// ---------- images ----------
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'optglb-'));
const imgRule = (name) => rules.images.find((r) => new RegExp(r.match).test(name)) || rules.images_default;
j.images.forEach((im, i) => {
  const bv = j.bufferViews[im.bufferView];
  const src = bin.subarray(bv.byteOffset || 0, (bv.byteOffset || 0) + bv.byteLength);
  const r = imgRule(im.name || '');
  const ext = im.mimeType === 'image/png' ? 'png' : 'jpg';
  const fi = path.join(tmp, `i${i}.${ext}`), fo = path.join(tmp, `o${i}.${r.format || ext}`);
  fs.writeFileSync(fi, src);
  const args = [fi, '-resize', `${r.max}x${r.max}>`, '-strip'];
  if ((r.format || ext) === 'jpg') args.push('-quality', String(r.quality || 82), '-sampling-factor', '4:2:0', '-interlace', 'none');
  args.push(fo);
  execFileSync('convert', args);
  const out = fs.readFileSync(fo);
  const use = out.length < src.length ? out : src;
  im.bufferView = addRaw(use);
  im.mimeType = (use === out ? (r.format || ext) : ext) === 'png' ? 'image/png' : 'image/jpeg';
  console.log('image', im.name, src.length, '->', use.length);
});
fs.rmSync(tmp, { recursive: true });

// ---------- materials: wire normal maps, sane PBR ----------
const imgIdx = (re) => j.images.findIndex((im) => new RegExp(re).test(im.name || ''));
j.textures = j.textures || [];
const texFor = (ii) => { let t = j.textures.findIndex((t) => t.source === ii); if (t < 0) { j.textures.push({ source: ii, sampler: 0 }); t = j.textures.length - 1; } return t; };
for (const m of j.materials) {
  const r = rules.materials[m.name] || {};
  m.pbrMetallicRoughness = m.pbrMetallicRoughness || {};
  m.pbrMetallicRoughness.metallicFactor = r.metallic ?? 0;
  m.pbrMetallicRoughness.roughnessFactor = r.roughness ?? 0.8;
  if (r.normal) { const ii = imgIdx(r.normal); if (ii >= 0) m.normalTexture = { index: texFor(ii), scale: r.normal_scale ?? 1 }; }
  if (r.baseColor) m.pbrMetallicRoughness.baseColorFactor = r.baseColor;
  if (r.alphaMode) m.alphaMode = r.alphaMode;
  if (r.doubleSided) m.doubleSided = true;
}
// drop unused images (textures nobody references)
const usedTex = new Set();
JSON.stringify(j.materials, (k, v) => { if (v && typeof v === 'object' && 'index' in v && !('bufferView' in v)) usedTex.add(v.index); return v; });

// ---------- layout ----------
let binOut = [], off = 0;
function put(bytes) { const o = off; binOut.push(Buffer.from(bytes)); off += bytes.length; const p = pad4(off) - off; if (p) { binOut.push(Buffer.alloc(p)); off += p; } return o; }
for (const c of comp) c.bv.extensions.EXT_meshopt_compression.byteOffset = put(c.enc);
for (const r of raw) r.bv.byteOffset = put(r.bytes);
j.bufferViews = views; j.accessors = accessors;
j.buffers = [{ byteLength: off }, { byteLength: fallbackLen, extensions: { EXT_meshopt_compression: { fallback: true } } }];
j.extensionsUsed = [...new Set([...(j.extensionsUsed || []), 'EXT_meshopt_compression', 'KHR_mesh_quantization'])];
j.extensionsRequired = [...new Set([...(j.extensionsRequired || []), 'EXT_meshopt_compression', 'KHR_mesh_quantization'])];
j.asset.generator = 'FBX2glTF + cgc-3d tools/opt-glb (meshopt v0, quantised normals/skin, ImageMagick textures)';
j.samplers = j.samplers && j.samplers.length ? j.samplers : [{}];

let js = Buffer.from(JSON.stringify(j), 'utf8'); js = Buffer.concat([js, Buffer.alloc(pad4(js.length) - js.length, 0x20)]);
const bb = Buffer.concat(binOut);
const head = Buffer.alloc(12); head.writeUInt32LE(0x46546c67, 0); head.writeUInt32LE(2, 4); head.writeUInt32LE(12 + 8 + js.length + 8 + bb.length, 8);
const ch = (len, type) => { const b = Buffer.alloc(8); b.writeUInt32LE(len, 0); b.writeUInt32LE(type, 4); return b; };
fs.writeFileSync(OUT, Buffer.concat([head, ch(js.length, 0x4e4f534a), js, ch(bb.length, 0x004e4942), bb]));
console.log('wrote', OUT, 12 + 8 + js.length + 8 + bb.length, 'bytes; compressed views', comp.length);

// ---------- verify every compressed view round-trips through three's MeshoptDecoder ----------
const { MeshoptDecoder } = await import(DECODER);
await MeshoptDecoder.ready;
let bad = 0, worst = 0;
for (const c of checks) {
  const t = new Uint8Array(c.count * c.stride);
  MeshoptDecoder.decodeGltfBuffer(t, c.count, c.stride, c.enc, 'ATTRIBUTES', c.filter ? 'EXPONENTIAL' : 'NONE');
  if (!c.filter) { if (Buffer.compare(Buffer.from(t), Buffer.from(c.bytes)) !== 0) bad++; continue; }
  const f = new Float32Array(t.buffer); const n = c.stride / 4;
  for (let i = 0; i < c.count; i++) { let mx = 0; for (let k = 0; k < n; k++) mx = Math.max(mx, Math.abs(c.floats[i * n + k]));
    for (let k = 0; k < n; k++) { const err = Math.abs(f[i * n + k] - c.floats[i * n + k]); const rel = mx ? err / mx : err; worst = Math.max(worst, rel); if (rel > 2 ** -10) bad++; } }
}
console.log('worst relative error of filtered views', worst);
console.log('round-trip', checks.length - bad, 'ok,', bad, 'bad');
if (bad) process.exit(1);
