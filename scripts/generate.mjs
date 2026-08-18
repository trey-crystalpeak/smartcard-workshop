import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const ROOT = path.join(import.meta.dirname, '..');
const PS = path.join(ROOT, 'pokemon-showdown');
const { Dex } = createRequire(import.meta.url)(path.join(PS, 'dist/sim'));
const dex = Dex.mod('gen9');

const POOL_SIZE = 64;
const MOVES_PER_SPECIES = 8;

const TYPES = ['Normal', 'Fighting', 'Flying', 'Poison', 'Ground', 'Rock', 'Bug', 'Ghost',
  'Steel', 'Fire', 'Water', 'Grass', 'Electric', 'Psychic', 'Ice', 'Dragon', 'Dark', 'Fairy'];
const NATURES = ['Hardy', 'Lonely', 'Brave', 'Adamant', 'Naughty', 'Bold', 'Docile', 'Relaxed',
  'Impish', 'Lax', 'Timid', 'Hasty', 'Serious', 'Jolly', 'Naive', 'Modest', 'Mild', 'Quiet',
  'Bashful', 'Rash', 'Calm', 'Gentle', 'Sassy', 'Careful', 'Quirky'];
const ITEMS = ['Leftovers', 'Sitrus Berry', 'Focus Sash', 'Life Orb', 'Assault Vest',
  'Safety Goggles', 'Choice Specs', 'Choice Band', 'Choice Scarf', 'Rocky Helmet',
  'Clear Amulet', 'Covert Cloak', 'Expert Belt', 'Weakness Policy', 'Wide Lens', 'Mental Herb'];

const TARGET_CLASS = { normal: 1, any: 1, adjacentFoe: 1, adjacentAlly: 2, adjacentAllyOrSelf: 2 };

const sets = JSON.parse(fs.readFileSync(path.join(PS, 'data/random-battles/gen9/doubles-sets.json'), 'utf8'));

const candidates = [];
for (const [id, entry] of Object.entries(sets)) {
  const species = dex.species.get(id);
  if (!species.exists) continue;
  if (species.requiredItem || species.requiredItems || species.battleOnly) continue;

  const movepool = [...new Set(entry.sets.flatMap((s) => s.movepool))];
  const abilities = [...new Set(entry.sets.flatMap((s) => s.abilities))];
  const teraTypes = [...new Set(entry.sets.flatMap((s) => s.teraTypes))];
  if (movepool.length < MOVES_PER_SPECIES) continue;

  let moves = movepool;
  if (moves.length > MOVES_PER_SPECIES) {
    const dmg = moves.filter((m) => dex.moves.get(m).category !== 'Status');
    const status = moves.filter((m) => dex.moves.get(m).category === 'Status');
    status.sort((a, b) => (b === 'Protect') - (a === 'Protect'));
    moves = [...dmg, ...status].slice(0, MOVES_PER_SPECIES);
  }
  if (moves.filter((m) => dex.moves.get(m).category !== 'Status').length < 3) continue;

  candidates.push({ id, species, level: entry.level, moves, abilities, teraTypes });
}

candidates.sort((a, b) => a.level - b.level);
const median = candidates[Math.floor(candidates.length / 2)].level;
candidates.sort(
  (a, b) => Math.abs(a.level - median) - Math.abs(b.level - median) || (a.id < b.id ? -1 : 1)
);
const chosen = candidates.slice(0, POOL_SIZE).sort((a, b) => (a.id < b.id ? -1 : 1));

const speciesOut = chosen.map((c) => ({
  name: c.species.name,
  level: c.level,
  types: c.species.types.map((t) => TYPES.indexOf(t)),
  bst: Object.values(c.species.baseStats).reduce((sum, stat) => sum + stat, 0),
  abilities: c.abilities,
  moves: c.moves.map((m) => {
    const move = dex.moves.get(m);
    return {
      name: move.name,
      type: TYPES.indexOf(move.type),
      bp: move.basePower,
      cat: move.category,
      targetClass: TARGET_CLASS[move.target] ?? 0,
    };
  }),
}));

const pool = { types: TYPES, natures: NATURES, items: ITEMS, species: speciesOut };
fs.writeFileSync(path.join(ROOT, 'data/pool.json'), JSON.stringify(pool, null, 2) + '\n');

const bytes = (arr) => arr.map((v) => `(byte) ${v}`).join(', ');
const teraA = chosen.map((c) => TYPES.indexOf(c.teraTypes[0]));
const teraB = chosen.map((c) => TYPES.indexOf(c.teraTypes[1] ?? c.teraTypes[0]));
const dmgMask = speciesOut.map((s) =>
  s.moves.reduce((m, mv, i) => (mv.cat !== 'Status' ? m | (1 << i) : m), 0)
);

fs.writeFileSync(path.join(ROOT, 'card/core/src/lrc/core/PoolData.java'), `package lrc.core;

public final class PoolData {
    public static final byte ITEM_COUNT = ${ITEMS.length};

    static final byte[] ABILITY_COUNT = { ${bytes(speciesOut.map((s) => s.abilities.length))} };
    static final byte[] TERA_A = { ${bytes(teraA)} };
    static final byte[] TERA_B = { ${bytes(teraB)} };
    static final byte[] DMG_MASK = { ${bytes(dmgMask)} };

    private PoolData() {}
}
`);
