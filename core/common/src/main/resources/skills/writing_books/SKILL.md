---
name: writing_books
description: Write in a book and quill and sign it into a written book with the write_book tool — where a book comes from, how pages and titles work, and why you ask the owner what to write before signing.
---

# Skill: writing_books

A book is how you leave something behind that outlives the conversation: a diary entry, a sign-in ledger for a chest, a dedication on a build, instructions for the next person. The vanilla editor is a CLIENT screen, which you do not have — so you write with `write_book`, which edits the pages on the item stack itself, exactly where the vanilla server writes them when a player signs a book.

## Where a book comes from

- You write in a **book and quill** (`minecraft:writable_book`): 1 book + 1 ink sac + 1 feather, shapeless. A book is 3 paper + 1 leather; ink sacs come from squid, feathers from chickens.
- A book and quill is crafted, never found: no vanilla loot table holds one. What chests do hold is plenty of **plain books** (village houses, stronghold libraries, abandoned mineshafts, dungeons, desert pyramids, jungle temples, pillager outposts) — grab one of those and add an ink sac and a feather.
- Nothing in your inventory? Fetch the materials (`collect_items`, `craft`, `lookup_recipe`) instead of standing over a lectern wondering — the tool tells you plainly when you are not carrying one.
- `inspect_gui` with no container open prints YOUR inventory with slot numbers; that is where the `slot` argument comes from (36-44 hotbar, 9-35 backpack, 45 off hand). You can also omit `slot` entirely — then the book in your hand is used.

## Writing pages

- `write_book action=write pages=["page one", "page two"]` replaces the whole book. `action=append` adds pages at the end and keeps what is already there. `action=set_page page_number=2 pages=["..."]` rewrites exactly one page (page numbers are 1-based, like a reader counts them).
- A book holds at most **100 pages of 1024 characters** each. Longer text gets split across pages at a paragraph or sentence boundary — never drop the tail silently, and never cut a word in half.
- Line breaks inside a page are ordinary newlines in the string (`\n`), so one page can hold a list or a poem.
- Keep prose plain. Vanilla tries to read each page as a JSON text component when the book is opened, so a page that happens to parse as JSON renders as a component rather than as your text — do not start a page with `{` by accident.
- The reply reports the slot, the page count and the character count. Use it: that is the only way the owner (who cannot see inside your inventory) learns what the book now contains.

## Signing

- `write_book action=sign title="Field Notes"` turns the book into `minecraft:written_book`: your name becomes the author, the title becomes the item's name (up to 32 characters).
- **Signing is final.** The text can never be edited or signed again, by you or by anyone. Treat it like handing over a written letter, because that is exactly what it is.
- Give the owner the text BEFORE signing. If they have not seen or dictated it, you are not ready to sign.
- Need a copy to give away? Vanilla book cloning on a crafting table (one signed book plus book and quill around it — see `lookup_recipe`) produces a copy; copies are marked as copies.

## Before you write: ask

- **Ask the owner what they want written.** The book carries THEIR words, not yours — do not invent content, names, dates, dedications or promises, and do not sign on your own initiative. If all you got was /write me a book/, ask what it should say and who it is for; one question is cheaper than a wrong book.
- Report back the title and page count when you are done, so they can ask for a change before you sign.
- If their text does not fit (over 100 pages, or a single page over 1024 characters), say so and propose a split — the limits are the game's, not yours to work around.

## Common mistakes

- **Right-clicking the book does nothing for you.** `interact_at` on a book and quill opens a screen that only a real client can type into; clicking slots never puts text in a book. Writing goes through `write_book`.
- **`write` replaces every page.** On a book that already has text you want to keep, use `append` (or `set_page`). The reply says how many pages the book has — read it before overwriting.
- **A signed book refuses further writes.** Editing one is impossible; fetch a fresh book and quill, and keep the signed original as it is.
- **A title that is too long is refused**, not truncated — shorten it, since a truncated title is a different name than the one the owner asked for.
