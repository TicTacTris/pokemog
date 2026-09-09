export const commit = '04ee0835e80f30c45376415725c0039cbdd12c5e'
export const sourceUrl = `https://raw.githubusercontent.com/pvpoke/pvpoke/${commit}/`

// Pure shared transform: never derive web metadata from generated Android assets.
export function evolutionMetadata(base, master) {
  const upstream = new Map(master.pokemon.map(p => [p.speciesId, p]))
  const ids = new Set(base.map(p => p.id))
  if (ids.size !== base.length || !base.length) throw new Error('Invalid bundled IDs')
  const report = { commit, missingFamilyMetadata: [], terminalWithFamilyMetadata: [], remappedShadowEdges: [], omittedShadowEdges: [], correctedEdges: [] }
  const knownUnavailable = new Set(['scyther_shadow:kleavor_shadow', 'girafarig_shadow:farigiraf_shadow', 'stantler_shadow:wyrdeer_shadow'])
  const metadata = Object.fromEntries(base.map(p => {
    const original = upstream.get(p.id)
    if (!original || !p.id.trim() || !p.name.trim() || [p.attack, p.defense, p.stamina].some(n => !Number.isSafeInteger(n) || n <= 0)) throw new Error(`Invalid Pokemon: ${p.id}`)
    if (p.attack !== original.baseStats.atk || p.defense !== original.baseStats.def || p.stamina !== original.baseStats.hp) throw new Error(`Base stats differ from pinned source: ${p.id}`)
    const shadow = p.id.endsWith('_shadow')
    if (!original.family) report.missingFamilyMetadata.push(p.id)
    else if (!original.family.evolutions?.length) report.terminalWithFamilyMetadata.push(p.id)
    const evolutions = (original.family?.evolutions ?? []).flatMap(target => {
      if (p.id === 'rockruff' && target === 'lycranroc_dusk') {
        report.correctedEdges.push([p.id, target, 'lycanroc_dusk'])
        target = 'lycanroc_dusk'
      }
      if (!ids.has(target) && knownUnavailable.has(`${p.id}:${target}`)) {
        report.omittedShadowEdges.push([p.id, target])
        return []
      }
      if (shadow && !target.endsWith('_shadow')) {
        if (ids.has(`${target}_shadow`)) {
          report.remappedShadowEdges.push([p.id, target, `${target}_shadow`])
          target = `${target}_shadow`
        } else {
          report.omittedShadowEdges.push([p.id, target])
          return []
        }
      }
      if (!ids.has(target)) throw new Error(`Unresolved evolution: ${p.id} -> ${target}`)
      return [target]
    })
    const normalId = shadow ? p.id.slice(0, -7) : null
    return [p.id, {
      evolutions: [...new Set(evolutions)],
      ...(shadow && ids.has(normalId) ? { normalId } : {}),
      ...(!shadow && ids.has(`${p.id}_shadow`) ? { shadowId: `${p.id}_shadow` } : {}),
      hasEvolutionData: !!original.family,
    }]
  }))
  const visited = new Set(), visiting = new Set()
  function visit(id) {
    if (visiting.has(id)) throw new Error(`Evolution cycle at ${id}`)
    if (visited.has(id)) return
    visiting.add(id)
    metadata[id].evolutions.forEach(visit)
    visiting.delete(id)
    visited.add(id)
  }
  base.forEach(p => visit(p.id))
  for (const [id, targets] of [['eevee', ['sylveon', 'umbreon']], ['cubone', ['marowak', 'marowak_alolan']], ['slowpoke_galarian', ['slowbro_galarian', 'slowking_galarian']], ['bulbasaur_shadow', ['ivysaur_shadow']]]) {
    if (ids.has(id) && !targets.every(t => metadata[id].evolutions.includes(t))) throw new Error(`Lost evolution branch: ${id}`)
  }
  return { metadata, report }
}
