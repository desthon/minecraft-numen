---
name: simple_farms
description: Build small, working automations with real numbers and a build ORDER - sugar cane / bamboo / cactus cell farms (observer + piston), a water-flush crop field, villager breeding (beds + food + a farmer), and a minimal mob tower (dark room + water sweep + fall kill). Sizes, material lists and trigger conditions only; load when the owner asks for "a farm", "so we stop running out of X", "an automatic X", or to feed a trade hall. Crop growth mechanics and animal pens alone are food_and_farming; the redstone parts themselves are redstone_basics.
---

# Skill: simple_farms

One rule above all others: **build one cell, verify it, then copy the cell.** Every failure in this
skill is a cell that was never tested repeated twenty times.

## What is true for every farm here

- **Light is the silent killer.** Crops (wheat/carrot/potato/beetroot) only grow where **internal
  light >= 9**; bamboo only grows when the **top of the stalk (or the shoot above a shoot) has light
  >= 9**; a mob tower needs the opposite - **block light 0** inside. Check the site before you lay a
  single block, and light underground farms or seal the mob tower properly.
- **Sugar cane, bamboo and cactus ignore light entirely** - they can be farmed in a dark cellar,
  which is exactly why these three are the beginner machines.
- **Growth is on random ticks, not a timer.** Sugar cane and cactus grow **one block after 16
  accumulated random ticks** near that block, up to a natural height of **3**. Do not promise the
  owner "a stack an hour"; say "it runs, it is slow, it is free".
- **The bot's job in a farm is mostly the boring half**: placing the field, the substrate, the water,
  and the sensors. **Placing water is `interact_at` with a water bucket** - `build` refuses liquids
  outright. Aim at the block the water should sit against, not at the empty cell.
- **`build` takes the whole machine in ONE call**, up to 16384 cells, and in survival it checks the
  materials **before** placing anything. So put the substrate, walls, water basins, hoppers and the
  redstone parts in the same op list, ordered bottom-to-top; a "wall done, roof refused" half-build
  is the failure mode when you split it.
- **Water sweeps and water collects.** A flowing stream breaks crops and carries the drops, and it
  **does not** carry off sugar cane, bamboo or cactus. Use it as free transport, and never let a
  flush level share a level with dust, torches or repeaters.
- **Hoppers are the bottleneck**: one hopper moves **1 item per 8 game ticks = 2.5 items/second**
  (~9000/hour). Fine for these farms. For a farm that floods items, use a hopper **minecart** on
  rails instead of a long hopper chain - far fewer parts and far less lag.

## 1. Sugar cane cell farm - 16 x 16 x 3

Sugar cane needs a substrate (sand/dirt/grass/moss, etc.) with **water adjacent to that substrate**,
and it grows to **3** blocks. It is **not** washed away by water, so breaking the upper blocks keeps
the field planted forever - **no replanting, ever**.

**Size:** 18 x 12 footprint, 3 high. A reusable **module** of 3 sand columns + 2 water channels;
copy the module to the desired length.

**Materials (for the 16-long version):** 80 substrate blocks (sand/dirt), 32 channel/floor blocks,
**6 water buckets' worth of water sources** (16 if you want both channels full of source blocks - see
step 4), 48 sugar cane, 1 hopper + 1 chest, plus the optional harvester below. One bucket is enough:
fill, scoop, refill.

**Build order:**

1. **Level and floor the site.** Flat ground is worth the ten minutes; every farm that "does not
   work" is usually built on a slope with the water running the wrong way.
2. Build the **substrate band**: `build` a solid band of `minecraft:sand` at **y=64**, **Z = 7..9**,
   **X = 0..15**. Keep the ground you stand on at y=63, so the channel floor and the sand are one
   block above it.
3. **Dig the two water channels**: 1 wide, at **Z = 6** and **Z = 10**, X = 0..15, with the water
   surface **at the sand's own level (y=64)** and one more block of water under it (y=63) so the
   channel never runs dry. The 3-wide sand band (Z = 7..9) is at that same y=64. Seal **Z = 5** and
   **Z = 11** with a full block so the channels cannot drain sideways onto the ground - the sand band
   already blocks the whole Z = 7..9 stretch, and the two seals close the outside faces.
4. **Pour the water, one source every 7 blocks.** A source spreads **7 blocks horizontally**, so in
   a 16-long channel you need **3 sources per channel** (X = 0, 7, 14), 6 buckets for the farm.
   Pour each one by aiming at the **block behind** the source cell, not at the empty cell:
   `interact_at(button=right, x=0, y=64, z=6, item_id="minecraft:water_bucket")`
   Then `inspect_block` a cell that should be water at the mid-channel (e.g. X=8, y=64, Z=6): if it
   reads air, two sources were spaced too far apart and the middle of the channel is dry.
   Keep **water only in the channels.** A source poured onto the sand itself spreads along the sand
   band and buries the substrate you just built.
5. **Plant.** Aim at the top face of each sand block, **not** at the air above it:
   `interact_at(button=right, x, y=64, z, item_id="minecraft:sugar_cane")`
   Each sand block in Z=7..9 is adjacent to water at the same level (Z=6 and Z=10), so all three
   columns are valid. A cane that "will not plant" is a substrate with no adjacent water.
   Write the coordinates down: the base of a planted stalk is **y=65**, the second block **y=66**,
   the third **y=67**.
6. **Harvest, one of two ways:**
   - *Manual (do this first):* `mine` the top two blocks, i.e. `mine(minecraft:sugar_cane, 2)` per
     stalk. The base stays; it regrows.
   - *Observer + piston unit (build ONE, verify it, then copy):* the trick is that the observer must
     sit **beside the cell it watches**, i.e. at the level of the cane's **second** block. Working
     with an observer at **X = x0** and a cane column at **Z = 9**:
     1. Observer: `set` `minecraft:observer` at **`(x0, 66, 8)`** with **`facing:"south"`** - its
        face watches `(x0, 66, 9)`, the empty cell the cane's second block grows into.
        `inspect_block` it and confirm the facing before you go on.
     2. Repeater: `set` `minecraft:repeater` at **`(x0+1, 66, 8)`** with **`facing:"west"`**,
        `properties:{delay:"1"}`. The observer's 2-tick pulse goes in its back and the repeater
        turns it into a pulse long enough for a piston to finish its stroke (the "pulse"; a repeater
        cannot split a pulse that short, so **use a repeater, not a comparator, to extend it**).
     3. Piston: `set` `minecraft:sticky_piston` at **`(x0+2, 66, 8)`** with **`facing:"west"`** -
        when it extends, the head reaches the cane column's second block and breaks it.
     4. Put a **lever on the observer's own block face** (aim at `(x0, 66, 8)` from the free side) so
        you can fire the piston by hand and watch it work **before** the electronics are finished.
        Remove it once the sensor chain is confirmed.
     The piston breaks the second block; the base at **y=65** survives and regrows, and anything at
     y=67 drops as an item. Nothing is ever replanted.
   - **If the piston does not extend**, the pulse was too short: put a second repeater in the chain,
     or a comparator. If it extends but nothing breaks, the piston is facing the wrong way -
     `inspect_block` it.
7. **Collection:** the channels already carry everything. Cap the far end (**X = 15**) with a solid
   block, then replace **one channel-floor block at X = 15** with a **hopper** pointing into a chest
   beside it - items swept along the channel end their trip sitting in the hopper's funnel and are
   taken. If the hopper does not get them, extend the channel one block and put the hopper under
   **that**.

**Verify a unit with:** `inspect_block` on the observer (right `facing`?), on the piston
(`extended`?), then trigger by hand - drop a cane block in the watched cell with `build` and see.

## 2. Bamboo farm - rows, observer + piston

Bamboo is **the fastest-growing plant in the game**: the top block, on a random tick, has a **1/3**
chance to grow. It grows on grass/dirt/sand/gravel etc. with **light >= 9** at the top, up to
**12-16** blocks tall. Breaking **any** block of a stalk breaks the whole stalk above it and drops
everything - so a single piston per plant is a full harvest.

**Size:** rows 1 apart; a 32-long, 2-row farm is a good first build.

**Materials:** 64 dirt (the rows), 64 bamboo, 1 water bucket per row, 2 observers, 2 sticky pistons,
2 repeaters, 2 levers (or one lever and a repeater fan-out), ~64 planks for the frame above.

**Build order:**

1. Lay the **planting row**: `build` dirt at **y=64**, one row along X. Rows may be adjacent.
2. Plant: `interact_at(button=right, x, y=64, z, item_id="minecraft:bamboo")`.
3. Build a **collection trench in front of the row**, 1 deep, and pour water at one end: bamboo
   drops are carried along it into a hopper + chest. Bamboo is **not** washed away - the trench only
   moves the *drops*.
4. Close the trench at the far end with a solid block and drop a hopper under it into a chest.
5. Install **one observer + sticky piston unit** at the row's head. The observer can only point
   sideways, so it must sit **beside** the growth cell at that cell's own level: with the bamboo base
   at **y=65**, a column at **Z = z**, and the observer at **Z = z+2**:
   1. `set` `minecraft:observer` at **`(x0, 65, z+2)`** with **`facing:"south"`** (toward the plant);
   2. `set` `minecraft:sticky_piston` at **`(x0, 65, z+1)`** with **`facing:"north"`** (toward the
      plant), so extending pushes the bamboo's first block off its base;
   3. `set` `minecraft:repeater` between, `properties:{delay:"1"}`, `facing` toward the piston.
   Breaking **any** block of a bamboo stalk brings down the whole stalk above it, so one unit is a
   full harvest. Verify the observer's `facing` and the piston's `extended` before copying the unit
   every 3 blocks along the row.
6. **Verify, then copy** the unit every 3 blocks along the row. Wire the row with repeaters rather
   than one long dust line - a dust run across the row above the pistons fires them all at once
   through **QC** (see `redstone_basics`). If the observer keeps firing (a feedback loop between the
   observer and the piston), put a **locked repeater** in the chain - that is the standard cure, and
   the wiki has a whole page for it (observer stabiliser).

## 3. Cactus farm - the self-breaking design

A cactus only survives if there is **no solid block or lava horizontally adjacent**, only **sand /
red sand / another cactus** below, and **no water or lava above**. It grows to **3** with no light
requirement. The whole automation is therefore **geometry, not redstone**: plant cacti one block
apart and every new block breaks itself the moment it grows into its neighbour.

**Materials:** sand for the columns, ~6 water buckets' worth of sources (3 channels x 2), 3 hoppers,
3 chests, ~128 building blocks for the surrounding wall, cacti.

**Build order:**

1. Lay **sand rows** with a **1-block gap** between them: sand at **Z = 8, 11, 14, 17**, X = 0..15,
   at **y=64**. The gap is what makes the neighbour-break work - do not close it.
2. Plant a cactus on every sand block: `interact_at(button=right, x, y=64, z, item_id="minecraft:cactus")`.
3. Dig the **collection channel under the gap**: the gap row (Z = 9, 12, 15) is exactly the channel.
   It must be **2 deep** (y=63 and y=64) with the sand row's y=64 face as its wall, so the water can
   both run along the channel and be adjacent to the sand at the substrate level. Pour one source
   every 7 blocks (a source spreads 7), and seal the channel's far end. **Never put water above a
   cactus and never pour onto the sand row** - water above kills the plant, and water on the sand
   carries the cactus items off the substrate where they cannot be harvested.
4. Put **hoppers + a chest** at the channel's end, **below** the drop, so items land in them.
5. Wall the whole farm in (`build` a ring of walls) - a dropped cactus that lands on a cactus is
   destroyed, and animals wandering into the field trample the substrate.

**Why it works:** when a cactus grows adjacent to the neighbouring column, it is instantly broken and
drops as an item - you never harvest anything, the machine harvests itself. Yield is lower than an
observer design; it is also almost free.

## 4. Water-flush crop field - 8 terraced rows, 9 wide

The cheapest way to harvest wheat/carrot/potato/beetroot without redstone: a water source released
**periodically at the top of a stepped field**, which breaks the crops on every row below it and
carries the whole harvest to one collection point. (A source merely *sitting* in the field is
irrigation, not a harvest - it is the flowing water arriving from upstream that breaks the plants.)

**Materials:** ~120 blocks of dirt/soil (8 terraces 9 wide, plus the trench walls), 4-5 water
buckets' worth of irrigation sources, 1 iron hoe, seeds/carrots/potatoes, 1 hopper, 1 chest,
optionally 1 lever + 1 dispenser for the repeatable flush.

**Build order:**

1. **Lay out the field as terraces, because water is the harvest.** The wiki is blunt about this
   design: the field has to accommodate the shape of the water flow and "may even be stepped". Build
   **8 planting rows (Z = 0..7)** that **drop one block every two rows** - Z = 0,1 at y=70, Z = 2,3 at
   y=69, Z = 4,5 at y=68, Z = 6,7 at y=67 - plus a **4-wide outflow trench at Z = 8, y=66**, one
   block below the last row. Water released at the high end runs down the whole field and out into
   the trench, and every drop comes with it. Give every step a **low wall at both X ends** so the
   water cannot run off the side, and the X range is 0..8 (9 wide) on every terrace.
2. **Till every row** with the hoe - aim at the block itself, not the air above it:
   `interact_at(button=right, x, y=<that row's level>, z, item_id="minecraft:iron_hoe")`.
3. **Keep the beds wet between flushes.** Farmland is wetted up to **4 blocks away** from water, so on
   **each terrace** put a water source **every 8 blocks along X, on that terrace's own level** (there
   is no separate channel - a source beside farmland simply wets it). Pour one by aiming at the block
   behind the source cell:
   `interact_at(button=right, x=0, y=70, z=0, item_id="minecraft:water_bucket")`
   Wet farmland grows crops faster than dry, and `inspect_block` on a farmland cell shows its
   moisture - **7 is fully wet**. These sources are the *irrigation*; the flush arrives from above
   later and washes over every terrace on its way down.
4. **Plant** by aiming at each farmland cell top:
   `interact_at(button=right, x, y=<row level>, z, item_id="minecraft:wheat_seeds")`
   (or carrot/potato/beetroot).
   **Plant single rows, or alternate two crops** - a plant surrounded by the *same* crop in the ring
   around it grows at roughly **half** speed.
5. **Fence it.** A field open to animals loses its farmland to trampling; a fence ring with one
   fence gate is the cheap, correct door.
6. **Harvest = flush.** The flush is a **second** water source released **on top of the top terrace**,
   not the irrigation: flowing water over a crop breaks it and carries the drops with it, and it
   **does not break the farmland underneath** - which is exactly why this design works at all. To
   test it the first time, stand at the upstream end and pour one bucket onto the top terrace so it
   runs downhill across all 8 rows:
   `interact_at(button=right, x=0, y=70, z=0, item_id="minecraft:water_bucket")`
   For a repeatable version, a **dispenser + lever** does the same job: aim the dispenser at the top
   terrace, load it with water buckets, and toggle the lever with
   `interact_at(button=right, x, y, z)`. Put the **hopper + chest in the outflow trench at Z = 8** -
   it is one block below the last row, so the water and the whole harvest drop straight into it.
7. **Replant** after the flush - the water destroys the crop plants, so this is a semi-automatic
   farm. That is normal for this design (the wiki calls it one), and it is much simpler than a
   piston harvester.

**If you do not want to replant by hand**, do not build this design - build the observer/piston
module from section 1 instead. The clean automatic crop farm needs a villager farmer (below) or a
flying machine, and both are a different project.

## 5. Villager breeding pen - beds, food, and one real farmer

Villagers do **not** breed by being fed directly the way animals do, but the mechanic is still food +
beds:

- They need **food** to become willing: **3 bread**, or **12 carrot**, **12 potato**, or **12
  beetroot** per breeding pair.
- The hard cap is **beds**: a villager will only breed if there is an **unclaimed bed** inside the
  village, and a bed is only "valid" if there are **two blocks of air above it**. A pen with more
  villagers than beds stops breeding, and the villagers show angry particles.
- Breeding only happens in **daytime**; the baby takes **20 minutes** to grow up.
- **A farmer feeds the others** - the farmer villager farms crops and throws the surplus to the
  others, so with a farmer and a reachable field the pen feeds itself and you never throw food
  again. That is the difference between a pen and a farm.

**Materials:** a wall/slab pen (or a fenced yard), a door, **1 composter** (exactly what makes a
farmer), **n + 2** beds for a target population of n, farmland + crops next to the pen, food for the
first round.

**Build order:**

1. Build the **pen**: solid ring of walls, 1 door, roof. Villagers cannot open a closed door; a pen
   without walls loses its villagers to mobs at night.
2. Put a **composter** inside the pen and let one villager claim it - that villager becomes the
   **farmer**, and the pen now self-feeds *if* there is a field it can reach. If there is no field,
   the farmer is just a villager.
3. Place **beds: one more than the number of villagers you want to end up with**, each with 2 air
   blocks above. Leave the extra beds unclaimed - they are the breeding slots.
4. Give the **first** food round by hand: stand between two villagers and throw bread (>= 3 each) or
   carrots/potatoes (>= 12 each) at them, or hand it over with `interact_entity` on each villager
   using `minecraft:bread`.
   `interact_entity(button=right, entity_id=<id>, item_id="minecraft:bread")`
5. **Wait in daylight** and watch for heart particles. Success = a baby villager.
6. **Do not kill, hit or let villagers die near the pen** - a villager death stops breeding in that
   village for about **3 minutes**.
7. To make it infinite (a real wall of villagers for a trade hall), unclaim the beds from the
   babies: let the **babies** not reach a bed (a separate holding cell), while the adults keep
   breeding on the free beds. The wiki's own "3 beds, infinite villagers" trick. Needs the owner's
   sign-off before you start moving villagers around.

**When you have more villagers than you need, hold them for the owner** - a librarian is worth a
fortune, and `villager_trading` is the pay-off.

## 6. Minimal mob tower - dark room + water sweep + fall kill

This is the classic four-part machine: **generate, concentrate, kill, collect**. It is also the farm
with the most ways to be quietly broken, so read the two conditions:

**Condition A - the room must be the only place mobs can spawn.** A hostile mob needs a valid spawn
position: **block light 0** (and internal light <= 7), a valid floor block, and a spot **more than
24 blocks** from the player but **within 128 blocks** (the despawn radius). Spawn candidates are the
chunks within **128 blocks horizontally** of a player. So: build the room **high above the terrain**
(or over a cleared area), because **every other spawnable surface within 128 blocks of your standing
spot steals the mob cap** (70 hostile mobs per player). Lighting up caves is hopeless on 1.20.1
terrain; go up instead.
**Condition B - things must die.** Fall damage is 1 HP per block of fall, and a zombie/skeleton/
creeper has **20 HP**, so the kill needs a drop of **23+ blocks**. Use **24** - round numbers are
easier to build and easier to check - and **verify it in game**: if anything is still walking, add a
block. Water at the bottom does **not** cushion: any entity in water takes no fall damage at all, so
the landing cell must be dry.

**Materials:** ~400 building blocks (9 x 9 room + a 26-high shaft + walls), 4 water buckets' worth
of sources, 1-2 hoppers, 1-2 chests, a stack of ladders/scaffolding for the climb, a stone sword
for finishing anything that survives.

**Build order:**

1. **Pick the site**: above water or above a hill you can climb, and on a column of air where **the
   nearest unlit spawnable terrain is more than 128 blocks away horizontally**. Mark the footprint
   with a few blocks, then get the owner's answer on where to stand while it runs - the AFK spot is
   part of the design, not an afterthought.
2. **Build the collection chamber**: a solid floor at **y=64** right under the shaft, walled so a
   mob that survives cannot walk out, with a **hopper** in the floor feeding a **chest** at the side
   (one hopper is enough at this scale). Leave the chamber open to walk into - you will be visiting
   it every time the chest is full.
3. **Build the drop shaft**: an enclosed 1 x 1 tube from the mob floor at **y=90** down to the
   **chamber floor at y=64** - the mob falls **26 blocks**, comfortably past the 24 it takes to kill
   anything with 20 HP. Build **one shaft level taller than you think you need**; shortening it later
   is easy, refilling it is not. The shaft must be fully enclosed: a mob that escapes into the world
   is a bug, not a drop. `build` the ring of walls in one call, leave the inside air, and put **no
   water** inside (water at the bottom would cancel the fall damage entirely).
4. **Build the dark room on top**: the spawn floor **is** the shaft's mob floor, so it sits at
   **y=90**, footprint 9 x 9, with **2 blocks of air** inside, walls all round, roof on. Mob spawn
   cells must be a **plain solid floor** (glass, trapdoors, bottom-slabs, leaves and ice are **not**
   spawnable) - and the floor has to be big enough to be worth it.
5. **Install the water sweep**: a **1-block-wide, 2-deep** channel along **two opposite edges** of
   the room, at the spawn-floor level, flowing **toward the centre**, and a matching pair of channels
   along the other axis that meet in the middle at the **hole above the shaft**. Mob AI tends to walk
   into water, so the flow does the herding for you. Pour with a bucket at the far end of each
   channel, aimed at the block **behind** the source cell; a source spreads **7 blocks**, so a
   9-long channel needs **2** sources (far end + middle). **Keep every channel at one level** with the
   spawn floor, and make sure the only way off the spawn floor is into a channel.
6. **Seal it.** Check the roof line and every corner for light leaks; `inspect_block` a few roof
   cells, and walk around outside at night to see if the tower glows. One gap = the room is lit = no
   spawns.
7. **Ladders / scaffolding** up the outside, and a small AFK platform at the top of the tower with
   a rail so the owner stands there while it runs. Standing **outside** the tower at ground level
   usually means ground spawns take the cap instead.
8. **Test**: stand on the platform for a few minutes. If nothing arrives, the causes in order of
   likelihood are - a light leak, a spawnable area within 128 blocks that you cannot see (a cave
   mouth, a tree canopy), or the drop being too short.
9. **Finish anything that survives** with `attack` at the collection end, and you have a mob tower
   with a real, if modest, output: gunpowder, arrows, bones, string, rotten flesh.

**Do not use a hopper chain for a big tower** - a hopper moves only 2.5 items/second. Use a hopper
minecart on a rail if the output ever becomes a problem.

## Build-order rule of thumb, for any farm

1. **Level the ground and check the light.**
2. **Substrate / floor first** (sand, dirt, farmland, spawn floor).
3. **Water second**, and seal its far end before you pour, so it cannot escape.
4. **Plant / populate third.**
5. **Sensors and actuators fourth** - one cell, verified with `inspect_block`.
6. **Collection last** (hoppers, chest, trench), and only then copy the module.
7. **Test with a real trigger**, then report the output rate honestly.

## What to load next

The parts themselves, facing rules and debugging -> `redstone_basics`; hunger, the crops as food and
animal pens -> `food_and_farming`; the hopper/sorter side of a big farm and where the output lives ->
`containers` and `storage_and_sorting`; what the villagers are for -> `villager_trading`; how to make
the machine look like it belongs -> `building_design`.
