---
name: food_and_farming
description: Hunger and saturation mechanics, why you must eat before a fight, and how to build a self-sustaining food supply - till and plant crops (light, water, spacing), breed cows/pigs/chickens, and cook the harvest. Load when food is low, when the owner asks for a farm or a pen, or before any long trip. Furnace/GUI mechanics live in containers.
---

# Skill: food_and_farming

Every long skill in this pack ends with "carry 32+ cooked food" - this is where that food comes
from, and why it is not optional.

## How hunger actually works

- The hunger bar is **20 points** (10 drumsticks). **Saturation** sits on top of it, is spent
  **first**, and can never exceed the hunger value. It starts at 5.
- **Exhaustion** accumulates from what you do; every **4 exhaustion** removes 1 point of saturation
  (or hunger, once saturation is gone):

| action | exhaustion |
|---|---|
| mining a block | 0.005 |
| swimming | 0.01 per metre |
| jumping | 0.05 |
| sprinting | 0.1 per metre |
| a melee hit | 0.1 |
| taking damage (0.1-type) | 0.1 |

- Health regenerates from food: **full hunger with saturation left heals fastest** (a pulse every
  0.5 s, spending saturation/6, up to 6 HP), and at hunger 18+ you still heal 1 HP every 4 s.
- **Hunger 0 damages you**: 1 HP every 4 s, ignoring armour and Resistance - and you cannot sleep.
- **Hunger 6 or below: no sprinting.** That is the difference between outrunning a creeper and not.
- So: **eat to full before a fight or a long trip**, not during one. Combat does not interrupt
  eating, but healing mid-fight is too slow to save you.

## Pick the right food

- Saturation = hunger x nutrition x 2. Nutrition grades: 1.2 (supernatural) / 0.8 (good) /
  0.6 (normal) / 0.3 (low) / 0.1 (poor).
- **Golden carrot is the best stackable food** - 14.4 saturation, and it needs no cooking (8 gold
  nuggets + 1 carrot).
- **Cook raw meat always.** Raw meat barely heals; cooked is the staple. One coal smelts 8 items,
  and a smoker finishes food in about half the time.
- Do not eat a snack onto a full bar - saturation above the cap is simply lost (the wiki's own
  example: eating a golden carrot at high hunger wastes part of it).
- Avoid rotten flesh (80% chance of the Hunger effect) and raw chicken (30%). Emergency only.

## Crops: the loop that never runs out

**Get seeds.** Break grass or ferns **by hand** (no shears) for wheat seeds; village farms and loot
chests are full of wheat/carrot/potato/beetroot starts. One seed is enough to multiply - only
mature plants return seeds, immature ones give back a single seed.

**Build the field:**

1. `interact_at(button=right, x, y, z, item_id=minecraft:iron_hoe)` on grass or dirt to make
   farmland.
2. Put a **water source** in the field (a bucket, or `interact_at` a water bucket at the field edge).
   Wet farmland grows faster; a single water block does not spoil the layout.
3. **Light matters more than anything**: crops only grow where **internal light >= 9**. An
   underground or covered farm needs torches/glowstone, or nothing grows at all.
4. Plant with `interact_at(button=right, x, y, z, item_id=minecraft:wheat_seeds)` (or
   carrot/potato/beetroot).
5. **Spacing**: more farmland around a plant and more wet farmland make it grow faster, but a plant
   with the **same crop** in the surrounding ring (unless it is a single row or column) grows at
   roughly **half speed**. Plant **single rows**, or alternate two crops - alternating keeps total
   yield nearly unchanged and is faster.
6. Fence the field: animals trample farmland, and the pathfinder's `do_not_break` protects fence
   gates, so a gate is safe to build.

**Harvest:** wheat, carrots and potatoes have 8 stages, beetroot 4. **Only mature plants give the
crop** - an immature one returns a single seed and wastes the plant. `mine` does **not** check age,
so before harvesting check the crop with `inspect_block` and read the `age` property (max age =
mature: 7 for wheat/carrot/potato, 3 for beetroot), or harvest a field you planted long enough ago.
Then `mine(minecraft:wheat, 64)`.

**Speed it up:** bone meal (1 bone = 3 bone meal; a full composter also yields 1) advances wheat,
carrots and potatoes 2-5 stages, and beetroot by one stage 75% of the time. Bees flying over a
field can also advance a crop a stage - a beehive plus flowers beside the field is free growth.

## Animals: a pen is meat, leather and wool forever

1. Fence **2+ animals** of the same species in (`build` a fence ring, or use an existing pen).
2. Feed two of them their breeding food with
   `interact_entity(button=right, entity_id=<id>, item_id=<food>)` - both get heart particles and a
   baby appears:

| animal | breeding food |
|---|---|
| cow, sheep | `minecraft:wheat` |
| pig | `minecraft:carrot` / `minecraft:potato` / `minecraft:beetroot` |
| chicken | `minecraft:wheat_seeds` |

3. A bred animal cannot breed again for **5 minutes**, and the baby takes **20 minutes** to grow up.
   So breed steadily and **slaughter only the surplus** - culling all your adults ends the farm.
4. Chickens also **lay eggs**: throwing an egg has a chance of hatching a chick, which is the
   fastest way to scale a chicken farm.
5. Sheep: shear with `minecraft:shears` (or dye first) instead of killing; they regrow wool after
   eating grass, so keep the pen on grass and keep a few alive.
6. Growth maths: each generation multiplies as X(next) = X + floor(X/2). Ten animals become fifteen,
   then twenty-two - plan the pen for that, not for two.

**Villager shortcut:** villages generate with farms and hay bales; each hay bale is 9 wheat, and 3
wheat make bread. A village also has a composter (bone meal) and often a smoker. If a village is
nearby, harvest it before you dig a farm.

## Cooking and stock

- Furnace or smoker: `interact_at` it, `transfer` the raw food in (no `to` - the menu routes it to
  the input slot) plus fuel below, then `set_timer` and collect the output. Exact slot mechanics:
  `containers`.
- Fuel: 1 coal = 8 items; charcoal from logs is the same; a smoker is roughly twice as fast for food.
- **Standing rule: never leave the base with fewer than 32 cooked food.** The Nether and the End
  trips want 64. Re-read `get_self_status` before a long `goto`.

## What to load next

Feeding a trade hall with crops -> `villager_trading` (a farmer buys crops, and villagers breed on
bread/carrots/potatoes/beetroot); furnaces and GUIs -> `containers`; the Nether trip that needs the
food -> `nether_entry`.
