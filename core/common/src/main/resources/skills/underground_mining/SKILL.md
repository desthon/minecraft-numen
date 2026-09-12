---
name: underground_mining
description: The digging itself - how to descend safely, the exact 1.20.1 ore layers per ore, what is underneath you at Y -59, cave and lava hazards, and how to take a vein without losing the drops. Load for "go mining", "get me iron/diamonds/redstone", strip-mining, or caving. The gear chain and recipes are tier_progression; this is where and how to dig.
---

# Skill: underground_mining

`tier_progression` says *what* to make and *which* tool tier unlocks which ore. This skill is the
other half: **where the ore actually is in 1.20.1, how to get down, and how not to lose the run to
lava.**

## The hard tool rules

1. **`mine` only harvests what your held tool can harvest.** Nothing else drops. `equip_item` the
   pickaxe before digging; a too-low tier destroys the block for nothing (the reply names the tier
   you are missing).
2. **`goto` digs with whatever is in your hand.** Descending with a sword equipped fails with "no
   path". Travel and dig with the pickaxe in the main hand and switch to a weapon only for a fight.
3. Always pass **both ids**: `iron_ore` **and** `deepslate_iron_ore`. Everywhere below Y 0 the
   deepslate variant replaces the normal one - every ore, without exception.
4. `count` is **items gained**, not blocks: `redstone_ore` drops 4-5, `copper_ore` 2-5 raw copper,
   `lapis_ore` 4-9, `nether_gold_ore` 2-6 nuggets, the rest 1.

## Where each ore is (1.20.1 worldgen)

| ore | Y range | densest Y | notes |
|---|---|---|---|
| coal | 0 - 320 | 96 (and uniform above 136) | **nothing at all below Y 0** |
| iron | -64 - 72 **and** 80 - 320 | 16; also 232 | **no iron between Y 73 and 79** |
| copper | -16 - 112 | 47-48 | ~2x bigger clusters in dripstone caves |
| lapis | -64 - 64 | 0 | also buried (never air-exposed) below 64 |
| gold | -64 - 32 | -16 | badlands add a huge second pass at Y 32-256 |
| redstone | -64 - 16 | lowest layers | densest right above bedrock |
| **diamond** | **-64 - 16** | **the deeper the better (-59)** | 4 cluster types, size 1-12 |
| emerald | -16 - 320 | 232 | **mountains only** (windswept hills etc.) |
| ancient debris (Nether) | 8 - 119 | 16 | needs a **diamond** pickaxe; ~1.5 per chunk |

Deepslate begins at **Y 0** (it fully replaces stone from -64 to 0, transitioning at Y 1-7). It is
slower to mine and drops **cobbled_deepslate** - which is useful throwaway scaffold, and it is on
the `scaffolds` list.

## What is under you at Y -59

In the Overworld, **lava replaces cave and canyon air between Y -63 and Y -55.** The diamond band
sits *inside* that lava layer, which is why deep mining kills careless players. On top of that:
lava lakes generate at any height, and below Y 0 some aquifers are **lava aquifers** instead of
water. Lava does *not* replace air inside mineshafts, dungeons or strongholds, so those are safer
to explore.

Discipline that actually works:

- **Never dig straight down** - the column below you is the one thing you cannot see.
- Before breaking a block at the bottom of a shaft, check it with `inspect_block` (it reports
  `is_liquid`), and `scan_blocks(radius=<64-192>, block_ids=[minecraft:lava])` - the matches carry
  `source:true/false`, and a source cell is the one that keeps flowing.
- Dig a **staircase** down rather than a shaft: you keep a walkable way back up and you expose more
  stone on the way.
- Carry a water bucket: pouring water on a lava edge is the only quick fix, and the pathfinder
  cannot help you once you are burning.

## Caves: what spawns and what is missing

- Hostile mobs need **block light 0** (and sky light <= 7). Torches are both map markers and
  spawn suppression; keep the corridor you are working in lit.
- **Coal does not generate below Y 0** - do not plan to make torches down there. Bring **5-7 stacks**
  of torches for a large noise cave (1 stack is the bare minimum), plus logs and a crafting table so
  you can resupply.
- A cave is not a shortcut to diamonds: exposed diamond ore has a **50-70% chance of being skipped
  at generation**, and the "buried" diamond clusters never touch air at all. If you want diamonds,
  **dig the stone** - caving alone systematically misses the buried ones.
- Cave fights happen in the dark with no room to retreat. Read `combat_basics` before caving, and
  leave the cave rather than fight at low HP.

## Take the whole vein, keep the drops

- `mine(block_ids=[...], count=N)` walks to each match, mines it, and **walks over the drops to pick
  them up** - do not hand-collect. Give it the real number you need.
- Fortune raises the **maximum** drop count of every ore except ancient debris; Silk Touch keeps the
  block itself (that is how you move deepslate and ender chests).
- Iron, copper and gold drop **raw** ore - smelt it (a furnace flow; see `containers`), or the
  `raw_*` blocks are dead weight in your pack.

## Set the "on the way" list before you go

`bonus_ores` is a standing, per-companion list: while mining the ore you were sent for, any ore on
that list that turns up within ~24 blocks is mined too instead of being walked past. Set it when the
owner says "grab any iron you see":

```
bonus_ores action=add block_ids=[minecraft:diamond_ore, minecraft:deepslate_diamond_ore, #minecraft:iron_ores]
```

It never changes the job you were given, and it cannot make you able to mine something - the reply
reports `not_harvestable_with_current_tool`, meaning "fetch the right pickaxe first".

## Packlist before descending

| item | why |
|---|---|
| Pickaxe (and a spare) | the whole trip depends on it |
| Torches, 5-7 stacks | light, spawn suppression, and coal stops existing deep down |
| Cooked food, 32+ | see `food_and_farming`; regen comes from hunger |
| Cobblestone / cobbled_deepslate | register it with `scaffold_materials` so the pathfinder may bridge and pillar with it |
| Bucket | water for lava emergencies |
| Sword + bow | the dark is full of mobs |
| Logs + crafting table | restock torches/tools mid-trip |
| A bed | set your respawn at the mine mouth and skip the night (`sleep`) |

**Report the coordinates of a good mine.** A dug staircase with ore at the bottom is a resource the
owner will come back to.

## What to load next

Smelting and furnaces -> `containers`; what to craft from the haul -> `tier_progression`; enchantments
for the pickaxe (Fortune, Efficiency, Mending) -> `enchant_and_repair`; Nether ancient debris ->
`nether_entry` and `blaze_rods`.
