// The hospital list (plan D14). hospitals.csv is the only source; this script checks it and writes
// hospitals.json, which the app bundles (app/src/main/assets/) and the website serves
// (data/hospitals.json) for the app's daily refresh.
//
//   node seed/hospitals.mjs          check the CSV and write seed/hospitals.json
//   node seed/hospitals.mjs --check  exit 1 if hospitals.json is out of date (CI)
//
// Rules (tested in rules-tests/hospitals.test.js):
// - every published change raises `# version:` in the CSV by 1; it never goes down. To undo a
//   change, put the old rows back as a new version;
// - only rows with verified=yes are published, and each needs a source link (the hospital's own
//   site or an official gov.bd page) that its name and address were checked against;
// - map points come only from OpenStreetMap (osmId), never from Google Maps. The JSON is therefore
//   ODbL 1.0: "Hospital locations © OpenStreetMap contributors".
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
export const CSV_PATH = join(HERE, 'hospitals.csv');
export const JSON_PATH = join(HERE, 'hospitals.json');
export const GEO_PATH = join(HERE, '..', '..', 'app', 'src', 'main', 'assets', 'bd-geo.json');

export const COLUMNS = ['id', 'name', 'nameBn', 'type', 'district', 'upazila', 'address', 'lat', 'lng', 'osmId',
  'hasBloodBank', 'source', 'verified', 'notes'];
export const TYPES = ['GOVERNMENT', 'PRIVATE', 'NGO', 'BLOOD_BANK'];
export const NAME_MAX = 120; // = Validators.HOSPITAL_NAME_MAX and the rules' hospitalName limit
export const ADDRESS_MAX = 200; // = Validators.HOSPITAL_ADDRESS_MAX
const ID = /^[a-z0-9-]{3,64}$/; // inside the rules' isId() ([A-Za-z0-9_-]{1,128})
const OSM_ID = /^(node|way|relation)\/[1-9][0-9]*$/;
// Bangladesh's bounding box, a little padded: catches swapped or mistyped coordinates.
const BD = { latMin: 20.5, latMax: 26.7, lngMin: 88.0, lngMax: 92.7 };

/** RFC 4180 CSV: quoted fields may hold commas, quotes ("") and line breaks. Lines starting with # are comments. */
export function parseCsv(text) {
  const comments = [];
  const records = [];
  let row = [], field = '', quoted = false, atLineStart = true;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') quoted = false;
      else field += c;
      continue;
    }
    if (atLineStart && c === '#') {
      const end = text.indexOf('\n', i);
      comments.push(text.slice(i + 1, end === -1 ? text.length : end).replace(/\r$/, '').trim());
      i = end === -1 ? text.length : end;
      continue;
    }
    atLineStart = false;
    if (c === '"') quoted = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\r') continue;
    else if (c === '\n') { row.push(field); records.push(row); row = []; field = ''; atLineStart = true; }
    else field += c;
  }
  if (quoted) throw new Error('hospitals.csv: a quoted field is never closed');
  if (field !== '' || row.length) { row.push(field); records.push(row); }
  return { comments, records: records.filter((r) => !(r.length === 1 && r[0] === '')) };
}

const B32 = '0123456789bcdefghjkmnpqrstuvwxyz';
/** Standard geohash (9 chars ≈ 5 m), as the app stores with a request's location. */
export function geohash(lat, lng, precision = 9) {
  const la = [-90, 90], lo = [-180, 180];
  let out = '', bits = 0, ch = 0, even = true;
  while (out.length < precision) {
    const [range, v] = even ? [lo, lng] : [la, lat];
    const mid = (range[0] + range[1]) / 2;
    if (v >= mid) { ch = ch * 2 + 1; range[0] = mid; } else { ch *= 2; range[1] = mid; }
    even = !even;
    if (++bits === 5) { out += B32[ch]; bits = 0; ch = 0; }
  }
  return out;
}

/** District code → its upazila / city-corporation codes, from bd-geo.json. */
export function loadAreas(geoText = readFileSync(GEO_PATH, 'utf8')) {
  const areas = new Map();
  for (const division of JSON.parse(geoText).divisions) {
    for (const d of division.districts) areas.set(d.code, new Set(d.upazilas.map((u) => u.code)));
  }
  return areas;
}

/**
 * Checks every row and returns { version, rows, published, problems }. `published` is the list
 * the JSON carries: verified rows only, in CSV order.
 */
export function readList(csvText, areas) {
  const problems = [];
  const { comments, records } = parseCsv(csvText);
  const versions = comments.map((c) => /^version:\s*(\d+)$/.exec(c)).filter(Boolean);
  const version = versions.length === 1 ? Number(versions[0][1]) : NaN;
  if (!(version >= 1)) problems.push('the CSV needs exactly one "# version: N" line (N ≥ 1)');
  const [header, ...data] = records;
  if (!header || header.join(',') !== COLUMNS.join(',')) {
    return { version, rows: [], published: [], problems: [...problems, `the header must be: ${COLUMNS.join(',')}`] };
  }
  const seen = new Set();
  const rows = data.map((cells, i) => {
    const line = `row ${i + 1}`;
    const bad = (msg) => problems.push(`${line} (${cells[0] || 'no id'}): ${msg}`);
    if (cells.length !== COLUMNS.length) { bad(`${cells.length} cells, expected ${COLUMNS.length}`); return null; }
    const r = Object.fromEntries(COLUMNS.map((k, j) => [k, cells[j].trim()]));
    if (!ID.test(r.id)) bad('id must be 3–64 of a-z, 0-9, -');
    if (seen.has(r.id)) bad('duplicate id');
    seen.add(r.id);
    for (const [k, v] of [['name', r.name], ['nameBn', r.nameBn]]) {
      if (v === '' && k === 'nameBn') continue;
      if (v.length < 2 || v.length > NAME_MAX) bad(`${k} must be 2–${NAME_MAX} characters`);
      if (v !== v.normalize('NFC')) bad(`${k} must be in NFC form`);
    }
    if (r.address.length > ADDRESS_MAX) bad(`address over ${ADDRESS_MAX} characters`);
    if (!TYPES.includes(r.type)) bad(`type must be one of ${TYPES.join(', ')}`);
    const upazilas = areas.get(r.district);
    if (!upazilas) bad(`district ${r.district} is not in bd-geo.json`);
    else if (r.upazila !== '' && !upazilas.has(r.upazila)) bad(`upazila ${r.upazila} is not in district ${r.district}`);
    const hasPoint = r.lat !== '' || r.lng !== '';
    if (hasPoint) {
      const lat = Number(r.lat), lng = Number(r.lng);
      if (r.lat === '' || r.lng === '') bad('lat and lng go together: both or neither');
      else if (!(lat >= BD.latMin && lat <= BD.latMax && lng >= BD.lngMin && lng <= BD.lngMax)) bad('the map point is outside Bangladesh');
      if (!OSM_ID.test(r.osmId)) bad('a map point must come from OpenStreetMap: set osmId (node/…, way/… or relation/…)');
    } else if (r.osmId !== '') bad('osmId is set but lat/lng are empty');
    if (!['', 'yes'].includes(r.hasBloodBank)) bad('hasBloodBank is "yes" or empty');
    if (!['yes', 'no'].includes(r.verified)) bad('verified is "yes" or "no"');
    // http is allowed: some official gov.bd sites have broken certificates.
    if (r.source !== '' && !/^https?:\/\/\S+$/.test(r.source)) bad('source must be one web link (http or https)');
    if (r.verified === 'yes' && r.source === '') bad('a verified row needs the source it was checked against');
    return r;
  }).filter(Boolean);
  const published = rows.filter((r) => r.verified === 'yes');
  return { version, rows, published, problems };
}

/** The JSON the app and the website use. Deterministic: the same CSV always gives the same bytes. */
export function toJson(version, published) {
  const hospitals = published.map((r) => {
    const h = { id: r.id, name: r.name };
    if (r.nameBn) h.nameBn = r.nameBn;
    h.type = r.type;
    h.district = r.district;
    if (r.upazila) h.upazila = r.upazila;
    if (r.address) h.address = r.address;
    if (r.lat !== '') {
      h.lat = Number(r.lat);
      h.lng = Number(r.lng);
      h.geohash = geohash(h.lat, h.lng);
    }
    if (r.hasBloodBank === 'yes') h.hasBloodBank = true;
    return h;
  });
  const doc = {
    version,
    licence: 'ODbL-1.0',
    attribution: 'Hospital locations © OpenStreetMap contributors',
    hospitals,
  };
  return JSON.stringify(doc, null, 1) + '\n';
}

/**
 * Why `next` may not replace `current` (both JSON texts; current is null before the first list), or
 * null if it may. Also refuses what phones would throw away (A's checks): no hospitals, over 2 MB,
 * or under 80% as many hospitals as the current list.
 */
export function versionProblem(current, next) {
  const b = JSON.parse(next);
  if (b.hospitals.length === 0) return 'no verified rows: phones refuse an empty list, so nothing is written';
  if (Buffer.byteLength(next) > 2 * 1024 * 1024) return 'over 2 MB: phones refuse it';
  if (current === null) return null;
  const a = JSON.parse(current);
  if (b.hospitals.length < 0.8 * a.hospitals.length) {
    return `${b.hospitals.length} hospitals is under 80% of the published ${a.hospitals.length}: phones would keep the old list`;
  }
  if (b.version < a.version) return `version ${b.version} is lower than the published ${a.version}: versions never go down`;
  const same = JSON.stringify(a.hospitals) === JSON.stringify(b.hospitals);
  if (!same && b.version === a.version) return `the rows changed: raise "# version:" to ${a.version + 1}`;
  if (!same && b.version > a.version + 1) return `raise the version by 1 (to ${a.version + 1}), not to ${b.version}`;
  if (same && b.version !== a.version) return 'the rows are unchanged: keep the version';
  return null;
}

/** The committed hospitals.json. Git on Windows (core.autocrlf) may check it out with CRLF; compare as LF. */
export function readCurrent() {
  return readFileSync(JSON_PATH, 'utf8').replace(/\r\n/g, '\n');
}

function main(args) {
  const { version, rows, published, problems } = readList(readFileSync(CSV_PATH, 'utf8'), loadAreas());
  if (problems.length) {
    console.error(`hospitals.csv has ${problems.length} problem(s):\n- ${problems.join('\n- ')}`);
    return 1;
  }
  const next = toJson(version, published);
  const current = existsSync(JSON_PATH) ? readCurrent() : null;
  if (args.includes('--check')) {
    if (current === null && published.length === 0) {
      console.log('no hospitals.json yet: no row is verified');
      return 0;
    }
    if (current !== next) {
      console.error('seed/hospitals.json is out of date: run `node seed/hospitals.mjs`');
      return 1;
    }
    console.log(`hospitals.json is up to date (version ${version}, ${published.length} hospitals)`);
    return 0;
  }
  const why = versionProblem(current, next);
  if (why) { console.error(why); return 1; }
  if (current === next) console.log(`no change (version ${version}, ${published.length} of ${rows.length} rows published)`);
  else {
    writeFileSync(JSON_PATH, next);
    console.log(`wrote seed/hospitals.json: version ${version}, ${published.length} of ${rows.length} rows (unverified rows left out)`);
  }
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) process.exitCode = main(process.argv.slice(2));
