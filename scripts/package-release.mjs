import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import {
  copyFileSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  lstatSync,
  utimesSync,
  chmodSync,
  rmSync,
  writeFileSync,
  existsSync,
} from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const root = fileURLToPath(new URL('../', import.meta.url))
const version = JSON.parse(readFileSync(join(root, 'package.json'))).version
assert.match(version, /^\d+\.\d+\.\d+$/)
const fingerprint = process.argv[2]
assert.match(
  fingerprint ?? '',
  /^[a-f0-9]{64}$/,
  'Expected dedicated release certificate SHA-256',
)
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT
assert.ok(sdk, 'Set ANDROID_HOME to the Android SDK')
function run(program, args, options = {}) {
  const result = spawnSync(program, args, { encoding: 'utf8', ...options })
  assert.equal(
    result.status,
    0,
    `${program} failed: ${result.stderr || result.error || ''}`,
  )
  return result.stdout
}
const apk = join(root, 'android/app/build/outputs/apk/release/app-release.apk')
const tools = join(sdk, 'build-tools/35.0.0')
const verification = run(join(tools, 'apksigner'), [
  'verify',
  '--verbose',
  '--print-certs',
  apk,
])
assert.ok(
  verification.includes(`Signer #1 certificate SHA-256 digest: ${fingerprint}`),
  'APK signer differs from dedicated release key',
)
assert.match(verification, /Number of signers: 1/)
const badging = run(join(tools, 'aapt'), ['dump', 'badging', apk])
assert.ok(
  badging.includes(
    `package: name='dev.pokemog.android' versionCode='12' versionName='${version}'`,
  ),
  'APK version/package mismatch',
)
assert.ok(
  !badging.includes('application-debuggable'),
  'Refusing debuggable APK',
)
const output = join(root, 'release')
mkdirSync(output, { recursive: true })
if (!existsSync(join(output, '.gitignore'))) {
  writeFileSync(join(output, '.gitignore'), '*\n', { flag: 'wx' })
}
const apkName = `pokemog-${version}.apk`
const zipName = `pokemog-web-${version}.zip`
assert.ok(
  !existsSync(join(output, apkName)) && !existsSync(join(output, zipName)),
  'Release assets already exist; move them aside before rebuilding',
)
const stage = join(output, '.web-stage')
assert.ok(!existsSync(stage), 'Staging directory already exists')
mkdirSync(stage)
try {
  const files = []
  function copy(directory, relative = '') {
    for (const name of readdirSync(directory).sort()) {
      assert.ok(!/[\r\n]/.test(name), 'Unsafe archive filename')
      const path = join(directory, name)
      const destination = join(relative, name)
      const info = lstatSync(path)
      assert.ok(!info.isSymbolicLink(), 'Symlinks are not release assets')
      if (info.isDirectory()) {
        mkdirSync(join(stage, destination))
        copy(path, destination)
      } else {
        assert.ok(info.isFile())
        assert.ok(
          !/\.(apk|aab|sha256|jks|keystore|p12|map)$/i.test(name) &&
            !name.startsWith('.') &&
            !/^readme/i.test(name),
          `Unexpected web asset: ${destination}`,
        )
        copyFileSync(path, join(stage, destination))
        chmodSync(join(stage, destination), 0o644)
        utimesSync(join(stage, destination), 946684800, 946684800)
        files.push(destination)
      }
    }
  }
  copy(join(root, 'dist'))
  assert.ok(
    files.includes('index.html') &&
      files.includes('fonts/Silkscreen-Regular.ttf') &&
      files.includes('fonts/Silkscreen-OFL.txt'),
  )
  assert.ok(
    readFileSync(join(stage, 'index.html'), 'utf8').includes(
      '/pokemog/assets/',
    ),
    'Web bundle must be built for Pages',
  )
  // Java 17's ZIP writer records no host ownership or private filesystem paths.
  run(
    'jar',
    [
      '--create',
      '--file',
      join(output, zipName),
      '--no-manifest',
      '--date=2000-01-01T00:00:00Z',
      ...files.sort(),
    ],
    { cwd: stage },
  )
  copyFileSync(apk, join(output, apkName))
  const sums = [apkName, zipName]
    .map((name) => {
      const line = `${createHash('sha256')
        .update(readFileSync(join(output, name)))
        .digest('hex')}  ${name}\n`
      writeFileSync(join(output, `${name}.sha256`), line)
      return line
    })
    .join('')
  writeFileSync(join(output, 'SHA256SUMS'), sums)
  writeFileSync(
    join(output, 'SIGNING-CERTIFICATE.txt'),
    `PokeMog release certificate SHA-256: ${fingerprint}\n`,
  )
  console.log(sums + verification)
} finally {
  rmSync(stage, { recursive: true, force: true })
}
