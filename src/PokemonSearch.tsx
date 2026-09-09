import { useEffect, useId, useRef, useState } from 'react'
import { catalog, canonicalPokemon, type Species } from './lib/projections'

const normalize = (value: string) =>
  value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()

export default function PokemonSearch({
  pokemon,
  onSelect,
  label = 'Pokemon species & form',
}: {
  pokemon: Species | null
  onSelect: (pokemon: Species) => void
  label?: string
}) {
  const id = useId()
  const [query, setQuery] = useState(pokemon?.name ?? '')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)
  const input = useRef<HTMLInputElement>(null)
  const list = useRef<HTMLUListElement>(null)
  const terms = normalize(query).trim().split(/\s+/).filter(Boolean)
  const matches = catalog.filter((p) =>
    terms.every((term) => normalize(p.name).includes(term)),
  )
  const shown = matches.slice(0, 50)

  useEffect(() => {
    if (open && active >= 0)
      list.current?.children[active]?.scrollIntoView({ block: 'nearest' })
  }, [active, open])

  function choose(match: Species) {
    onSelect(match)
    setQuery(canonicalPokemon(match).name)
    input.current?.focus()
    setOpen(false)
    setActive(-1)
  }

  return (
    <>
      <label htmlFor={`${id}-search`}>{label}</label>
      <div
        className="pokemon-search"
        onBlur={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget)) {
            setOpen(false)
            setActive(-1)
            setQuery(pokemon?.name ?? '')
          }
        }}
      >
        <input
          ref={input}
          id={`${id}-search`}
          role="combobox"
          autoComplete="off"
          spellCheck={false}
          aria-autocomplete="list"
          aria-expanded={open}
          aria-controls={`${id}-options`}
          aria-activedescendant={
            open && active >= 0
              ? `${id}-option-${shown[active]?.id}`
              : undefined
          }
          aria-describedby={`${id}-search-hint`}
          placeholder="Search species or form..."
          value={query}
          onFocus={(event) => {
            setOpen(true)
            event.currentTarget.select()
          }}
          onClick={() => setOpen(true)}
          onChange={(event) => {
            setQuery(event.target.value)
            setOpen(true)
            setActive(-1)
          }}
          onKeyDown={(event) => {
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
              event.preventDefault()
              setOpen(true)
              setActive(
                shown.length
                  ? event.key === 'ArrowDown'
                    ? Math.min(active + 1, shown.length - 1)
                    : active <= 0
                      ? shown.length - 1
                      : active - 1
                  : -1,
              )
            } else if (event.key === 'Enter' && open) {
              event.preventDefault()
              const match = shown[active < 0 ? 0 : active]
              if (match) choose(match)
            } else if (event.key === 'Escape') {
              setOpen(false)
              setActive(-1)
              setQuery(pokemon?.name ?? '')
            }
          }}
        />
        {open && (
          <div className="search-popover">
            <p className="search-status" role="status">
              {matches.length
                ? `${matches.length.toLocaleString('en-US', { maximumFractionDigits: 1 })} matches${matches.length > shown.length ? ' / first 50 shown; type to narrow' : ''}`
                : 'No matching Pokemon. Try another species or form.'}
            </p>
            <ul
              id={`${id}-options`}
              ref={list}
              className="search-options"
              role="listbox"
              tabIndex={-1}
              aria-label="Matching Pokemon"
            >
              {shown.map((match, index) => (
                <li
                  key={match.id}
                  id={`${id}-option-${match.id}`}
                  role="option"
                  aria-selected={index === active}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => choose(match)}
                >
                  {match.name}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
      <p className="helper search-hint" id={`${id}-search-hint`}>
        {open
          ? pokemon
            ? `Select a match to update. Showing ${pokemon.name}.`
            : 'Select a species and form from the matches.'
          : 'Search by species or form, then select a match.'}
      </p>
    </>
  )
}
