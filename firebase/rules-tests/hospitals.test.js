// The hospital list (plan D14): seed/hospitals.csv → seed/hospitals.json. No emulator needed; it runs
// with the rules tests so CI checks it on every push.
import { describe, test, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import {
  CSV_PATH, JSON_PATH, COLUMNS, NAME_MAX, parseCsv, geohash, loadAreas, readList, toJson, versionProblem,
} from '../seed/hospitals.mjs';

const areas = loadAreas();
const csv = readFileSync(CSV_PATH, 'utf8');
const list = readList(csv, areas);
const header = COLUMNS.join(',');
/** A one-row CSV built from a valid row, with some cells changed. */
const row = (over = {}) => {
  const r = {
    id: 'dhk-test', name: 'Test Hospital', nameBn: 'টেস্ট হাসপাতাল', type: 'GOVERNMENT', district: 'BD3026',
    upazila: 'BD30262000', address: 'Somewhere, Dhaka', lat: '23.7256', lng: '90.39757', osmId: 'way/1',
    hasBloodBank: '', source: 'https://example.gov.bd/', verified: 'yes', notes: '', ...over,
  };
  return COLUMNS.map((k) => (/[",\n]/.test(r[k]) ? `"${r[k].replaceAll('"', '""')}"` : r[k])).join(',');
};
const sheet = (...rows) => `# version: 1\n${header}\n${rows.join('\n')}\n`;
const problemsOf = (...rows) => readList(sheet(...rows), areas).problems;

describe('hospitals.csv', () => {
  test('has no problems: unique ids, bd-geo codes, names ≤ 120, map points from OSM, all-or-no location', () => {
    expect(list.problems).toEqual([]);
    expect(list.rows.length).toBeGreaterThan(0);
  });

  test('every id is unique and every code is a real bd-geo district and one of its upazilas', () => {
    expect(new Set(list.rows.map((r) => r.id)).size).toBe(list.rows.length);
    for (const r of list.rows) {
      expect(areas.has(r.district), r.id).toBe(true);
      if (r.upazila) expect(areas.get(r.district).has(r.upazila), r.id).toBe(true);
    }
  });

  test('a row has both lat and lng or neither, and every published name fits the request form', () => {
    for (const r of list.rows) {
      expect(r.lat === '', r.id).toBe(r.lng === '');
      expect(r.name.length, r.id).toBeLessThanOrEqual(NAME_MAX);
      expect(r.nameBn.length, r.id).toBeLessThanOrEqual(NAME_MAX);
    }
  });
});

describe('hospitals.json', () => {
  test('is exactly what the CSV produces (run `node seed/hospitals.mjs` after editing the CSV)', () => {
    const next = toJson(list.version, list.published);
    if (!existsSync(JSON_PATH)) {
      expect(list.published, 'rows are verified but seed/hospitals.json was never written').toEqual([]);
      return;
    }
    expect(readFileSync(JSON_PATH, 'utf8')).toBe(next);
  });

  test('has the shape the app and the website read', () => {
    const doc = JSON.parse(toJson(list.version, list.published));
    expect(Object.keys(doc)).toEqual(['version', 'licence', 'attribution', 'hospitals']);
    expect(doc.version).toBe(list.version);
    expect(doc.licence).toBe('ODbL-1.0');
    for (const h of doc.hospitals) {
      expect(['lat', 'lng', 'geohash'].filter((k) => k in h).length % 3, h.id).toBe(0); // all three or none
      if (h.geohash) expect(h.geohash).toBe(geohash(h.lat, h.lng));
    }
  });
});

describe('the checks catch mistakes', () => {
  test('a valid row passes', () => {
    expect(problemsOf(row())).toEqual([]);
  });

  test.each([
    ['a duplicate id', [row(), row()], 'duplicate id'],
    ['an id with capitals or spaces', [row({ id: 'Dhk Test' })], 'id must be'],
    ['a name over 120 characters', [row({ name: 'H'.repeat(121) })], 'name must be 2–120'],
    ['a Bangla name over 120 characters', [row({ nameBn: 'হ'.repeat(121) })], 'nameBn must be 2–120'],
    // ড় and য় are composition exclusions: their NFC form is the letter + nukta, so the one-character
    // forms (U+09DC, U+09DF) are the ones refused. The app's search folds both sides to NFC too.
    ['a Bangla name not in NFC form (one-character ড়)', [row({ nameBn: 'বাড়ি হাসপাতাল' })], 'NFC'],
    ['an unknown district', [row({ district: 'BD9999', upazila: '' })], 'not in bd-geo'],
    ['an upazila from another district', [row({ upazila: 'BD40410023' })], 'not in district BD3026'],
    ['lat without lng', [row({ lng: '' })], 'both or neither'],
    ['a point outside Bangladesh (lat/lng swapped)', [row({ lat: '90.39757', lng: '23.7256' })], 'outside Bangladesh'],
    ['a map point with no OSM source', [row({ osmId: '' })], 'must come from OpenStreetMap'],
    ['an osmId without a point', [row({ lat: '', lng: '' })], 'osmId is set'],
    ['an unknown type', [row({ type: 'CLINIC' })], 'type must be'],
    ['a verified row without a source', [row({ source: '' })], 'needs the source'],
    ['a source that is not one web link', [row({ source: 'dghs website' })], 'one web link'],
    ['two links in one source', [row({ source: 'https://a.gov.bd/ https://b.gov.bd/' })], 'one web link'],
    ['a typo in verified', [row({ verified: 'Yes' })], 'verified is'],
  ])('refused: %s', (_name, rows, message) => {
    expect(problemsOf(...rows).join('\n')).toContain(message);
  });

  test('no version line, two version lines, or a wrong header', () => {
    expect(readList(`${header}\n${row()}\n`, areas).problems.join()).toContain('version');
    expect(readList(`# version: 1\n# version: 2\n${header}\n${row()}\n`, areas).problems.join()).toContain('version');
    expect(readList(`# version: 1\nid,name\n${row()}\n`, areas).problems.join()).toContain('header');
  });

  test('a row without a map point is fine (no Open in Maps for it), and unverified rows are not published', () => {
    const { problems, published } = readList(sheet(row({ lat: '', lng: '', osmId: '' }), row({ id: 'dhk-other', verified: 'no', source: '' })), areas);
    expect(problems).toEqual([]);
    expect(published.map((r) => r.id)).toEqual(['dhk-test']);
    expect(JSON.parse(toJson(1, published)).hospitals[0]).not.toHaveProperty('lat');
  });

  test('quoted cells keep their commas and quotes', () => {
    const { records } = parseCsv('a,b\n"x, y","say ""hi"""\n');
    expect(records[1]).toEqual(['x, y', 'say "hi"']);
  });

  test('geohash matches the published reference value', () => {
    expect(geohash(57.64911, 10.40744, 11)).toBe('u4pruydqqvj');
  });
});

describe('the version rule: +1 for every published change, never down', () => {
  const v = (version, names) => toJson(version, names.map((name, i) => ({
    id: `dhk-h${i}`, name, nameBn: '', type: 'PRIVATE', district: 'BD3026', upazila: '', address: '', lat: '', lng: '', hasBloodBank: '',
  })));
  test.each([
    ['the first list', null, v(1, ['A']), null],
    ['a change with version + 1', v(1, ['A']), v(2, ['A', 'B']), null],
    ['nothing changed', v(2, ['A']), v(2, ['A']), null],
    ['a change without a new version', v(1, ['A']), v(1, ['B']), 'raise'],
    ['a version that skips', v(1, ['A']), v(3, ['B']), 'by 1'],
    ['a version that goes down (an undo must be a new version)', v(3, ['A']), v(2, ['B']), 'never go down'],
    ['a new version with no change', v(1, ['A']), v(2, ['A']), 'unchanged'],
    ['an empty list (phones refuse it)', null, v(1, []), 'empty'],
    ['a list under 80% of the current one (phones keep the old)', v(1, ['A', 'B', 'C', 'D', 'E']), v(2, ['A', 'B', 'C']), '80%'],
    ['exactly 80% is fine', v(1, ['A', 'B', 'C', 'D', 'E']), v(2, ['A', 'B', 'C', 'D']), null],
  ])('%s', (_name, current, next, message) => {
    const why = versionProblem(current, next);
    if (message === null) expect(why).toBeNull();
    else expect(why).toContain(message);
  });
});
