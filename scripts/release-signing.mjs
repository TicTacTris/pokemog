import { randomBytes, createHash } from 'node:crypto'
import {
  mkdirSync,
  writeFileSync,
  readFileSync,
  lstatSync,
  chmodSync,
  existsSync,
} from 'node:fs'
import { homedir } from 'node:os'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

process.umask(0o077)
const root = fileURLToPath(new URL('../', import.meta.url))
const directory = join(homedir(), '.local/share/pokemog-signing')
const secretsPath = join(directory, 'secrets.json')
const command = process.argv[2]
if (!['init', 'build', 'certificate'].includes(command))
  throw new Error(
    'Usage: node scripts/release-signing.mjs init|build|certificate',
  )
function run(program, args, env, capture = false) {
  const result = spawnSync(program, args, {
    cwd: root,
    env,
    stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit',
  })
  if (result.error || result.status !== 0)
    throw new Error(`${program} failed; no release was packaged`)
  return result.stdout
}
function privatePath(path, mode) {
  const info = lstatSync(path)
  if (
    info.isSymbolicLink() ||
    info.uid !== process.getuid() ||
    (info.mode & 0o777) !== mode
  ) {
    throw new Error(`Unsafe ownership or permissions: ${path}`)
  }
}
if (command === 'init') {
  if (existsSync(directory))
    throw new Error(
      'Signing directory already exists; refusing to replace any key or credentials',
    )
  mkdirSync(directory, { mode: 0o700 })
  const password = randomBytes(48).toString('base64url')
  const secrets = {
    storePassword: password,
    keyPassword: password,
    alias: 'pokemog-release',
    keystorePath: join(directory, 'pokemog-release.p12'),
  }
  writeFileSync(secretsPath, `${JSON.stringify(secrets)}\n`, {
    mode: 0o600,
    flag: 'wx',
  })
  run(
    'keytool',
    [
      '-genkeypair',
      '-keystore',
      secrets.keystorePath,
      '-storetype',
      'PKCS12',
      '-alias',
      secrets.alias,
      '-keyalg',
      'RSA',
      '-keysize',
      '4096',
      '-sigalg',
      'SHA256withRSA',
      '-validity',
      '10000',
      '-dname',
      'CN=PokeMog Release',
      '-storepass:env',
      'POKEMOG_STORE_PASSWORD',
      '-keypass:env',
      'POKEMOG_KEY_PASSWORD',
    ],
    {
      ...process.env,
      POKEMOG_STORE_PASSWORD: password,
      POKEMOG_KEY_PASSWORD: password,
    },
    true,
  )
  chmodSync(secrets.keystorePath, 0o600)
}
privatePath(directory, 0o700)
privatePath(secretsPath, 0o600)
const secrets = JSON.parse(readFileSync(secretsPath, 'utf8'))
if (
  resolve(secrets.keystorePath) !== join(directory, 'pokemog-release.p12') ||
  secrets.alias !== 'pokemog-release'
)
  throw new Error('Unexpected release key location or alias')
privatePath(secrets.keystorePath, 0o600)
const env = {
  ...process.env,
  POKEMOG_KEYSTORE_FILE: secrets.keystorePath,
  POKEMOG_KEYSTORE_PASSWORD: secrets.storePassword,
  POKEMOG_STORE_PASSWORD: secrets.storePassword,
  POKEMOG_KEY_ALIAS: secrets.alias,
  POKEMOG_KEY_PASSWORD: secrets.keyPassword,
}
const certificate = run(
  'keytool',
  [
    '-exportcert',
    '-keystore',
    secrets.keystorePath,
    '-alias',
    secrets.alias,
    '-storepass:env',
    'POKEMOG_STORE_PASSWORD',
  ],
  env,
  true,
)
const fingerprint = createHash('sha256').update(certificate).digest('hex')
console.log(`Release certificate SHA-256: ${fingerprint}`)
if (command === 'build') {
  // No persistent Gradle daemon/configuration cache may retain the signing credentials.
  run(
    './android/gradlew',
    [
      '-p',
      'android',
      '--no-daemon',
      '--no-configuration-cache',
      ':app:verifyReleaseSigning',
      ':app:assembleRelease',
      ':app:assembleDebugAndroidTest',
      ':app:testDebugUnitTest',
      ':app:lintRelease',
    ],
    env,
  )
  // Web tooling never receives the signing environment. Rebuild Pages last, from this source version.
  run('npm', ['run', 'build'], {
    ...process.env,
    POKEMOG_BASE_PATH: '/pokemog/',
  })
  run(
    process.execPath,
    ['scripts/package-release.mjs', fingerprint],
    process.env,
  )
}
