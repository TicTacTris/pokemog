import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';

// Keep the web-parser/catalog baseline intact except for these two intentional
// native whitespace improvements. Re-run after regenerating the baseline fixture.
const path = new URL('../android/app/src/test/resources/appraisal-text.json', import.meta.url);
const originalText = await readFile(path, 'utf8');
const original = JSON.parse(originalText);
const fixture = structuredClone(original);
const corrections = [
  { text: 'CP\n123', field: 'cp', value: 123 },
  { text: 'HP\n100', field: 'hp', value: 100 },
];
const matched = [];
let updated = 0;
for (const { text, field, value } of corrections) {
  const indices = fixture.cases.flatMap((entry, index) => entry.text === text ? [index] : []);
  assert.equal(indices.length, 1, `Expected exactly one case for ${JSON.stringify(text)}`);
  const index = indices[0];
  const previous = fixture.cases[index][field];
  assert.ok(previous === null || previous === value, `Unexpected baseline ${field} for ${JSON.stringify(text)}`);
  if (previous !== value) updated++;
  fixture.cases[index][field] = value;
  matched.push({ index, field });
}
assert.equal(matched.length, 2);

// Verify that all catalog data, metadata, and other expected fields are unchanged.
const unchanged = structuredClone(fixture);
for (const { index, field } of matched) unchanged.cases[index][field] = original.cases[index][field];
assert.deepEqual(unchanged, original);
await writeFile(path, JSON.stringify(fixture) + originalText.match(/\s*$/)[0]);
assert.deepEqual(JSON.parse(await readFile(path, 'utf8')), fixture);
console.log(`${fixture.cases.length} cases; ${matched.length} corrections matched; ${updated} fields updated; all other data unchanged.`);
