import { useDeferredValue, useEffect, useRef, useState } from 'react'
import PokemonSearch from './PokemonSearch'
import dataLicense from './data/PVPoke-LICENSE.txt?raw'
import {
  assessPokemon,
  canonicalPokemon,
  catalog,
  levelAlternatives,
  type AssessmentInput,
  type EvolutionProjection,
  type LeagueAssessment,
} from './lib/projections'
import type { Stats } from './lib/calculations'
import './App.css'

const attributes = ['attack', 'defense', 'stamina'] as const
const labels = { attack: 'Attack IV', defense: 'Defense IV', stamina: 'HP IV' }
const number = (n: number) =>
  n.toLocaleString('en-US', { maximumFractionDigits: 2 })
const integer = (s: string, min: number, max = Number.MAX_SAFE_INTEGER) =>
  /^\d+$/.test(s) &&
  Number.isSafeInteger(Number(s)) &&
  Number(s) >= min &&
  Number(s) <= max

function StatLine({ value, shadow }: { value: Stats; shadow: boolean }) {
  return (
    <>
      <dl className="stat-line" data-testid="actual-stats">
        <div>
          <dt>CP</dt>
          <dd>{value.cp}</dd>
        </div>
        <div>
          <dt>HP</dt>
          <dd>{value.hp}</dd>
        </div>
        <div>
          <dt>ATK</dt>
          <dd>{number(value.attack)}</dd>
        </div>
        <div>
          <dt>DEF</dt>
          <dd>{number(value.defense)}</dd>
        </div>
      </dl>
      {shadow && (
        <p className="shadow-note">
          Shadow ATK equivalent: {number(value.attack * 1.2)} (x1.2 damage only;
          not actual ATK). Also takes x1.2 damage.
        </p>
      )}
    </>
  )
}

function Projection({
  projection: p,
  shadow,
  maxBase,
  futureBuddy,
}: {
  projection: EvolutionProjection
  shadow: boolean
  maxBase: number
  futureBuddy: boolean
}) {
  return (
    <article className="projection">
      <h3>
        {canonicalPokemon(p.pokemon).name}{' '}
        {p.isEvolution && <span className="tag">Evolution</span>}
      </h3>
      {p.shadowUnverified && (
        <p className="shadow-note">
          Shadow availability / evolution path unverified. Theoretical stats
          only.
        </p>
      )}
      {!canonicalPokemon(p.pokemon).hasEvolutionData && (
        <p className="warning">
          Family metadata missing; further evolutions may not be listed.
        </p>
      )}
      <p className={`feasibility ${p.feasibility.code}`}>
        {p.feasibility.message}
      </p>
      <h4>
        {p.isEvolution ? 'After evolution, no power-ups' : 'Current stats'}
      </h4>
      {p.current.length ? (
        p.current.map((c) => (
          <div key={c.level}>
            <p className="helper">Effective level {c.level}</p>
            <StatLine value={c.stats} shadow={shadow} />
          </div>
        ))
      ) : (
        <p className="helper">
          Unknown. Enter both observed CP and maximum HP, or confirm a base
          level.
        </p>
      )}
      <h4>League optimum</h4>
      {p.optimal ? (
        <>
          <p className="helper">
            Effective level {p.optimal.level} /{' '}
            {futureBuddy && p.optimal.level > maxBase
              ? `base ${p.optimal.level - 1}, future Buddy on`
              : `base ${p.optimal.level}, Buddy off`}
          </p>
          <StatLine value={p.optimal} shadow={shadow} />
          <p className="ranking">
            Rank #{p.optimal.rank} / {number(p.eligibleSpreads)} eligible
            spreads
            <br />
            {p.ivPercentile?.toFixed(2)}% IV percentile /{' '}
            {p.percentBest?.toFixed(2)}% of best stat product
          </p>
        </>
      ) : (
        <p>No eligible build.</p>
      )}
      <h4>Fully powered up</h4>
      <p className="helper">
        Base {maxBase}, future Buddy {futureBuddy ? 'on (+1)' : 'off'} /
        effective {p.maximum.level}
      </p>
      <StatLine value={p.maximum.stats} shadow={shadow} />
    </article>
  )
}

function LeagueCard({ league }: { league: LeagueAssessment }) {
  const p = league.evolutions[0]
  const percentile = p.ivPercentile ?? 0
  return (
    <section
      className={`league-card league-${league.cap}`}
      aria-label={league.name}
    >
      <div className="card-heading">
        <h2>{league.name}</h2>
      </div>
      <div className="arc">
        <svg viewBox="0 0 220 120" aria-hidden="true">
          <path
            className="arc-track"
            d="M 15 110 A 95 95 0 0 1 205 110"
            pathLength="100"
          />
          <path
            className="arc-fill"
            d="M 15 110 A 95 95 0 0 1 205 110"
            pathLength="100"
            strokeDasharray={`${percentile} 100`}
          />
        </svg>
        <div>
          <strong data-testid="percentile">
            {p.ivPercentile === null ? '--' : p.ivPercentile.toFixed(1)}
            {p.ivPercentile !== null && <small>%</small>}
          </strong>
          <span>PvP IV percentile</span>
        </div>
      </div>
      {p.feasibility.code !== 'unknown' && (
        <p className="card-warning">
          {p.feasibility.code === 'over-cap'
            ? 'Over cap; cannot power down.'
            : p.feasibility.code === 'above-level-cap'
              ? 'Above selected level limit.'
              : p.feasibility.code === 'uncertain'
                ? 'Reachability uncertain; check Details.'
                : p.feasibility.code === 'no-build'
                  ? 'No eligible build.'
                  : p.feasibility.message.includes('unequip')
                    ? 'Remove active Buddy boost.'
                    : ''}
        </p>
      )}
    </section>
  )
}

function App() {
  const [form, setForm] = useState({
    pokemon: catalog.find((p) => p.id === 'azumarill')!,
    attack: '0',
    defense: '15',
    stamina: '15',
    cp: '',
    hp: '',
    level: '',
    shadow: false,
    activeBuddy: 'unknown' as AssessmentInput['activeBuddy'],
    maxBaseLevel: 50 as 40 | 50,
    allowBestBuddy: false,
  })
  const deferred = useDeferredValue(form)
  const [dark, setDark] = useState(() => {
    try {
      return localStorage.getItem('pokemog-theme') === 'dark'
    } catch {
      return false
    }
  })
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light'
    try {
      localStorage.setItem('pokemog-theme', dark ? 'dark' : 'light')
    } catch {
      /* Storage is optional in private contexts. */
    }
  }, [dark])
  const invalid =
    attributes.some((a) => !integer(form[a], 0, 15)) ||
    [form.cp, form.hp].some((s) => s !== '' && !integer(s, 10)) ||
    (form.level !== '' &&
      (!/^\d+(?:\.5|\.0)?$/.test(form.level) ||
        Number(form.level) < 1 ||
        Number(form.level) > 50))
  const f = deferred
  const deferredInvalid =
    attributes.some((a) => !integer(f[a], 0, 15)) ||
    [f.cp, f.hp].some((s) => s !== '' && !integer(s, 10)) ||
    (f.level !== '' &&
      (!/^\d+(?:\.5|\.0)?$/.test(f.level) ||
        Number(f.level) < 1 ||
        Number(f.level) > 50))
  const assessment =
    invalid || deferredInvalid
      ? null
      : assessPokemon({
          pokemon: f.pokemon,
          ivs: {
            attack: Number(f.attack),
            defense: Number(f.defense),
            stamina: Number(f.stamina),
          },
          shadow: f.shadow,
          observedCp: f.cp === '' ? undefined : Number(f.cp),
          observedHp: f.hp === '' ? undefined : Number(f.hp),
          baseLevel: f.level === '' ? undefined : Number(f.level),
          activeBuddy: f.activeBuddy,
          maxBaseLevel: f.maxBaseLevel,
          allowBestBuddy: f.allowBestBuddy,
        })
  const themeButton = (
    <button
      className="theme-button"
      type="button"
      aria-label="Dark theme"
      aria-pressed={dark}
      onClick={() => setDark(!dark)}
    >
      {dark ? 'Light' : 'Dark'}
    </button>
  )
  return (
    <div className="app-shell">
      <header className="topbar">
        <a
          className="brand"
          href={import.meta.env.BASE_URL}
          aria-label="PokeMog home"
        >
          <span className="brand-mark" aria-hidden="true">
            P
          </span>
          <span>
            PokeMog<small>PVP IVs on the GO</small>
          </span>
        </a>
        <div className="header-actions">
          {themeButton}
          <button
            type="button"
            aria-label="Open menu"
            onClick={() => dialog.current?.showModal()}
          >
            <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
              <path
                fill="currentColor"
                d="M1 2h14v2H1zm0 5h14v2H1zm0 5h14v2H1z"
              />
            </svg>{' '}
            Menu
          </button>
        </div>
      </header>
      <main className="workspace">
        <section className="panel inputs" aria-labelledby="input-heading">
          <div className="section-title">
            <span className="step">01</span>
            <h1 id="input-heading">Your Pokemon</h1>
          </div>
          <PokemonSearch
            pokemon={form.pokemon}
            onSelect={(raw) =>
              setForm({
                ...form,
                pokemon: canonicalPokemon(raw),
                shadow: Boolean(raw.normalId || /shadow/i.test(raw.id)),
              })
            }
          />
          <button
            type="button"
            role="switch"
            aria-checked={form.shadow}
            className={`shadow-switch ${form.shadow ? 'enabled' : ''}`}
            onClick={() => setForm({ ...form, shadow: !form.shadow })}
          >
            <span className="pixel-ghost" aria-hidden="true" />
            <span>
              Shadow <small>{form.shadow ? 'ON' : 'OFF'}</small>
            </span>
            <span className="switch-track" aria-hidden="true" />
          </button>
          <h2 className="input-subtitle">Appraisal IVs</h2>
          <div className="iv-grid">
            {attributes.map((a) => (
              <label key={a} htmlFor={a}>
                {labels[a]}
                <input
                  id={a}
                  aria-label={labels[a]}
                  type="text"
                  inputMode="numeric"
                  autoComplete="off"
                  value={form[a]}
                  aria-invalid={!integer(form[a], 0, 15)}
                  onChange={(e) => setForm({ ...form, [a]: e.target.value })}
                />
                <span className="iv-bar" aria-hidden="true">
                  <i
                    style={{
                      width: `${integer(form[a], 0, 15) ? (Number(form[a]) / 15) * 100 : 0}%`,
                    }}
                  />
                </span>
                <small>0 - 15</small>
              </label>
            ))}
          </div>
          <details className="advanced">
            <summary>Advanced settings</summary>
            <h2 className="input-subtitle">
              Current readings <small>Optional</small>
            </h2>
            <div className="reading-grid">
              <label>
                Observed CP
                <input
                  inputMode="numeric"
                  type="text"
                  placeholder="Unknown"
                  value={form.cp}
                  aria-invalid={form.cp !== '' && !integer(form.cp, 10)}
                  onChange={(e) => setForm({ ...form, cp: e.target.value })}
                />
              </label>
              <label>
                Maximum HP
                <input
                  inputMode="numeric"
                  type="text"
                  placeholder="Unknown"
                  value={form.hp}
                  aria-invalid={form.hp !== '' && !integer(form.hp, 10)}
                  onChange={(e) => setForm({ ...form, hp: e.target.value })}
                />
              </label>
            </div>
            <p className="helper">
              Use total HP, not remaining HP. Both readings are needed to infer
              a level.
            </p>
            <label>
              Known base level
              <input
                inputMode="decimal"
                type="text"
                placeholder="Unknown (1-50)"
                value={form.level}
                onChange={(e) => setForm({ ...form, level: e.target.value })}
              />
            </label>
            <p className="helper">
              Half-level steps. Excludes the active Buddy boost.
            </p>
            <label>
              Current Buddy boost
              <select
                aria-label="Current Buddy boost"
                value={form.activeBuddy}
                onChange={(e) =>
                  setForm({
                    ...form,
                    activeBuddy: e.target
                      .value as AssessmentInput['activeBuddy'],
                  })
                }
              >
                <option value="unknown">Unknown</option>
                <option value="off">Off</option>
                <option value="on">On (+1 level)</option>
              </select>
            </label>
            <label>
              Maximum base level
              <select
                aria-label="Maximum base level"
                value={form.maxBaseLevel}
                onChange={(e) =>
                  setForm({
                    ...form,
                    maxBaseLevel: Number(e.target.value) as 40 | 50,
                  })
                }
              >
                <option value="40">40 / No XL</option>
                <option value="50">50 / XL allowed</option>
              </select>
            </label>
            <label className="checkbox">
              <input
                type="checkbox"
                checked={form.allowBestBuddy}
                onChange={(e) =>
                  setForm({ ...form, allowBestBuddy: e.target.checked })
                }
              />
              Allow future Best Buddy (+1)
            </label>
          </details>
        </section>
        <div className="results" aria-busy={form !== deferred}>
          <div className="section-title">
            <span className="step">02</span>
            <div>
              <h2>League potential</h2>
              <p className="helper">
                {form.pokemon.name} / {form.attack || '?'} /{' '}
                {form.defense || '?'} / {form.stamina || '?'}
              </p>
            </div>
          </div>
          {invalid && (
            <p role="alert" className="warning">
              Enter all three IVs as whole numbers from 0 to 15. Optional CP and
              HP must be whole numbers of at least 10; base level must be 1-50
              in half-level steps. Blank IVs are not zero.
            </p>
          )}
          {assessment && (
            <>
              {assessment.leagues[0].evolutions[0].shadowUnverified && (
                <p className="shadow-note">Shadow availability unverified.</p>
              )}
              {assessment.levelMessage.startsWith('Warning:') && (
                <p className="warning">
                  Readings contradict the confirmed level. Check Advanced
                  settings.
                </p>
              )}
              {f.cp !== '' &&
                f.hp !== '' &&
                !assessment.effectiveLevels.length && (
                  <p className="warning">
                    CP/HP do not match this form and IVs. Check Advanced
                    settings.
                  </p>
                )}
              <div className="league-grid">
                {assessment.leagues.slice(0, 2).map((league) => (
                  <LeagueCard key={league.cap} league={league} />
                ))}
              </div>
              <details className="panel detail-drawer">
                <summary>Expand details</summary>
                <p className="helper explanation">
                  PvP IV percentile compares your rank with eligible IV spreads
                  of this form. Percent of best compares stat products, not
                  percentile. Neither is a win chance. Pokemon cannot power
                  down.
                </p>
                <div className="level-note">
                  <p>{assessment.levelMessage}</p>
                  {levelAlternatives(assessment.scenarios).map((line) => (
                    <p className="level-alternative" key={line}>
                      {line}
                    </p>
                  ))}
                </div>
                {assessment.leagues.map((league) => (
                  <details
                    className="league-details"
                    key={league.cap}
                    open={league.cap === 1500}
                  >
                    <summary>
                      {league.name} / {number(league.cap)} CP
                    </summary>
                    {league.cap === 500 && (
                      <p className="warning">
                        Little League cup eligibility varies. A CP fit does not
                        guarantee eligibility.
                      </p>
                    )}
                    {league.evolutions.map((p) => (
                      <Projection
                        key={p.pokemon.id}
                        projection={p}
                        shadow={assessment.shadow}
                        maxBase={f.maxBaseLevel}
                        futureBuddy={f.allowBestBuddy}
                      />
                    ))}
                  </details>
                ))}
              </details>
            </>
          )}
          <p className="field-note">
            A good spread is just the start. Moves, matchups and cup rules
            matter too.
          </p>
        </div>
      </main>
      <footer>
        <span>Built for the next battle.</span>
        <span>Manual entry / Local calculations / Offline ready</span>
        <a href={`${import.meta.env.BASE_URL}privacy/`}>Privacy</a>
      </footer>
      <dialog ref={dialog} aria-labelledby="menu-title">
        <div className="dialog-heading">
          <h2 id="menu-title">PokeMog menu</h2>
          <button
            type="button"
            onClick={() => dialog.current?.close()}
            autoFocus
          >
            Close
          </button>
        </div>
        <h3>Instructions</h3>
        <p>
          <a href={`${import.meta.env.BASE_URL}downloads/index.html`}>
            Download PokeMog for Android
          </a>
        </p>
        <p>
          Select the exact species and form. Enter the three appraisal IVs
          (0-15). Advanced settings optionally accept observed CP and maximum HP
          to estimate effective level. Expand details for level alternatives,
          technical stats and evolution paths.
        </p>
        <h3>Settings</h3>
        {themeButton}
        <p>
          Theme is saved on this device when storage is available. Power-up
          settings live in the Advanced drawer.
        </p>
        <h3>Disclaimers</h3>
        <p>
          Unofficial fan tool, not affiliated with Pokemon or Niantic. Bundled
          data may include unreleased forms. Missing family metadata is not
          proof of a final evolution. Shadow availability is separate from
          theoretical stats. Shadow damage does not change CP, HP, actual
          ATK/DEF or IV rank. No uploads or OCR.
        </p>
        <h3>Optional Support</h3>
        <p>
          PokeMog is a passion project. Free to use. All features are available
          without payment.
        </p>
        <p>
          Ko-fi is strictly for optional tips. Tips are never required to
          download, use, or access any feature, and do not unlock functionality
          or promise support.
        </p>
        <p>
          <a
            href="https://ko-fi.com/tictactris"
            target="_blank"
            rel="noreferrer"
          >
            Optional tips on Ko-fi
          </a>
          . This external site receives connection information only when you
          follow the link; its own privacy policy applies.
        </p>
        <p>
          <a href={`${import.meta.env.BASE_URL}privacy/`}>
            Privacy and Android SDK disclosures
          </a>
        </p>
        <h3>Licenses</h3>
        <p>
          Original code is MIT licensed. Android includes proprietary Google ML
          Kit, so the complete app is not fully FOSS. Pokemon and Pokeball
          trademark rights are not granted. This unofficial beta is not endorsed
          or authorized by the rights holders; a fan-use or fair-use disclaimer
          is not a legal guarantee.
        </p>
        <p>
          Pokemon data: <a href="https://github.com/pvpoke/pvpoke">PvPoke</a>.
          Font: Silkscreen,{' '}
          <a href={`${import.meta.env.BASE_URL}fonts/Silkscreen-OFL.txt`}>
            SIL Open Font License
          </a>
          .
        </p>
        <details>
          <summary>PvPoke license</summary>
          <pre>{dataLicense}</pre>
        </details>
      </dialog>
    </div>
  )
}

export default App
