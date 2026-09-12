---
name: village_loot
description: Loot a village (or any world-generated structure) for free starter gear — find it, walk there, open every chest, take what is useful. Load when starting from nothing, when you need food/iron/emeralds fast, or when your owner says "loot a village".
---

# Skill: village_loot

A village is the fastest start in the game. Beds, food, iron, emeralds, tools and arrows are
already sitting in its chests — no mining, no smelting, no waiting. One village run often
skips most of a hand-dug iron-and-food grind.

## When this is worth it

- The owner says "find a village / loot a village / get us started".
- You are starting from nothing and want iron + food + a bed without digging for it.
- You are short of a specific thing villages carry: iron ingots, emeralds, arrows, buckets,
  saplings, cooked food, wool.

It is **not** free: the walk can be hundreds of blocks. If you only need one stack of
cobblestone, dig it; do not walk 400 blocks for that.

## Steps

1. `locate_structure("#minecraft:village")` — the tag covers every village variant, so you get
   the nearest one whatever biome it is in. Read the coordinates it gives you.
2. `goto(x, y, z)` to that x/z. Aim for the surface y the locate result reported; if it is
   off, `goto` the x/z, then `scan_blocks` around you to find the ground.
3. `scan_blocks(radius=64, block_ids=[chest, trapped_chest, barrel])` — chests are what you are
   here for. Do it again from a second spot if the village is long: one scan only covers what
   is loaded around you.
4. For each chest, in order (nearest first):
   - `interact_at(button=right, x, y, z)` — opens it
   - `inspect_gui` — shows its slots *and* your own inventory in one listing
   - `transfer moves=[{from:S1}, {from:S2}, …]` — one call, one entry per slot you want,
     **omit the `to`**: the menu routes each stack into your inventory
   - `close_gui`

   Take whole stacks. Partial takes are for when your inventory is nearly full.
5. Count what you actually got and say it to the owner. "Village at 320,-60 got me 14 iron,
   3 cooked porkchops and a bed" is the kind of report that makes the trip worth it.

## What to take, what to leave

| Take | Why |
|---|---|
| Iron ingots, gold, emeralds | Tools/armor, and emeralds buy the rest |
| Cooked food | Healing; you will not have to hunt for a while |
| Arrows, bows, iron gear | Free combat kit |
| Buckets, shears, saplings, wool | Things that are annoying to make |

Leave the junk (wheat seeds, spare leather helmets) when your inventory is tight — you have to
carry all of it.

## Do not do these

- **Do not loot your owner's base, or any chest inside a player-built structure.** Villages are
  world-generated and fair game; a chest in someone's house is not. If you cannot tell whether a
  chest was generated with the world, ask before opening it — loot is not worth breaking trust.
- **Do not hit villagers.** And your `do_not_break` guard already covers their beds and doors —
  do not work around it.
- **Do not fight the iron golem.** If it turns on you (it does when your village reputation is
  bad), walk away; it is not worth the fight and it is not worth dying over.
- Do not stop at the first chest and leave. Villages have four to eight of them, plus a
  blacksmith with the good stuff.

## After the loot

A village is also a place to come back to: beds to sleep (and skip the night), a bell, and
farmers you can trade with if you ever have emeralds. **Report the coordinates to your owner** —
a village is a landmark, and they may want to visit it.
