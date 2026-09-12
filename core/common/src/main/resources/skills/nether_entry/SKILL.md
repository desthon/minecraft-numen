---
name: nether_entry
description: Acquire obsidian, build a Nether portal with build, ignite it with flint & steel via interact_at, and enter the Nether with the right packlist.
---

# Skill: nether_entry

Phase 2 of the dragon route. Build a portal, ignite it, walk through. Actual Nether survival starts in `blaze_rods`.

## Done when

- A lit Nether portal stands at a known overworld location (**report its coordinates to your owner** — it's the way home)
- You are standing in the Nether with the packlist below intact

## Obsidian (need 10)

Two routes. Take the first when a ruined portal exists; the second always works.

**A. Mine a ruined portal (preferred).** It is standing obsidian, already made, with no fluid to
fight.

1. `locate_structure("#minecraft:ruined_portal")` — searches the whole family and returns the nearest. **Skip `ruined_portal_ocean`** (underwater) if the result names it; re-search or pick a land one — I can't dive.
2. `equip_item(diamond_pickaxe)` (obsidian needs diamond), `goto` the portal coordinates.
3. `mine(obsidian, 10)` — it digs the frame's obsidian on its own. ~9.4s per block is normal.

Notes:
- A portal's frame mixes plain **obsidian** with **crying obsidian** (purple particles). Crying obsidian is a *different block and useless for a portal frame* — `mine(obsidian)` already ignores it, so a single portal may yield fewer than 10. If you come up short, `locate_structure("#minecraft:ruined_portal")` again for the next nearest and top up.
- Some ruined portals sit in a lava pocket. If mining starts costing you HP to the lava beside the
  block you are cutting, relocate to a cleaner portal rather than fighting the fluid.

**B. Cast your own (water over lava).** The classic method, and it works: pour water onto a lava
pool and the lava it lands on turns to obsidian. What it costs is *care and time*, not permission.

1. Find a lava **pool** you can stand beside safely (`scan_blocks` for `lava` — a pool, not a
   single source block in a wall).
2. Stand on solid ground at the edge, **not** over the lava.
3. `interact_at(button=right, x, y, z, item_id=minecraft:water_bucket)` aimed a couple of cells
   into the lava surface. Water flows over it and the lava underneath becomes obsidian. Work a few
   cells at a time — each pour makes a small slab you can step back from.
4. **Take the water back**: `interact_at(button=right, x, y, z, item_id=minecraft:bucket)` on the
   water source cell, or the flow keeps spreading and the bucket is gone.
5. `mine(obsidian, 10)` the slab you cast. Those cells sit on top of the pool, so you are mining
   *beside* lava — do not walk into the hole you are cutting.

Casting is slower per block and more dangerous than route A, but a ruined portal is rarely where
you want the frame. Do it when nothing ruined is nearby, or when the owner wants the portal at a
specific spot.

## Portal build

- Frame: 4 wide × 5 tall, **corners omitted = exactly 10 obsidian**, standing vertically. Inner opening is 2×3 air.
- Pick flat ground near your base. Build the frame with one `build` call: two side columns of 3, plus top and bottom rows of 2. A single-cell `build` call handles any one-off correction.
- **Flint & steel**: craft `flint_and_steel` = 1 iron ingot + 1 flint, a 2×2 recipe (`lookup_recipe` + `transfer` into your own grid; see the `containers` skill). Flint drops from `mine(gravel)`, ~10%/block.
- **Ignite**: `interact_at(button=right, x, y, z, item_id=minecraft:flint_and_steel)` aimed at an **empty air cell INSIDE the frame** (a bottom one), not at the obsidian. The fire lands in that cell and the portal forms.
- Enter: `goto` the portal cell and stand in it until the dimension changes (`get_self_status` confirms).

## Packlist (verify with `get_self_status` before igniting)

| Item | Qty | Why |
|---|---|---|
| Cooked food | 32+ | Your healing |
| Diamond sword + bow | 1 + 1 | Equip for combat only — hold the pickaxe while travelling (navigation digs with the held tool) |
| Arrows | 32+ | `attack` spends them only on what it cannot reach (~6 per blaze); run low → carry extra food and let it melee |
| Diamond pickaxe (+ iron backup) | 1 + 1 | Obsidian, digging |
| Cobblestone | 64+ | Navigation scaffold — bridging lava lakes eats it |
| Gold helmet (worn) | 1 | Piglin truce; 5 gold ingots if you must craft one |
| Flint & steel | 1 | Re-light the portal if a ghast blows it out |

**Never place or use a bed in the Nether — beds explode there.**

## Nether ground rules

- **Don't dig straight down**; lava oceans sit under most terrain. Navigation bridges lava when it must — keep cobblestone stocked.
- **Water doesn't exist here**: buckets won't place.
- **Zombified piglins are pacifists until hit — and then they ALL swarm.** Never `attack` them.
- **Ghasts** snipe from far; their fireballs can break the portal. On arrival, note the Nether-side portal coordinates (`get_self_status`) and report them to your owner. Overworld↔Nether coordinates map 8:1 horizontally.

## What to load next

Standing in the Nether, packlist intact → mark phase 2 `completed`, `load_skill(name="blaze_rods")`. Load `combat_basics` too if you haven't — blazes are the first real combat test.

