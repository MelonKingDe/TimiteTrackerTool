# Collection Tracker (Fabric 26.2)

Client-side mod for Hypixel Skyblock. A permanent HUD that shows your progress in **any collection**
against **any rival**, and how much you need to collect per day to catch up by **your own deadline**.

Defaults: collection `timite`, rival `dinofeet_`, deadline `2027-01-01`.

## What it shows
- your amount and rank, the rival's amount (or the current #1 with `rival top`)
- the gap, days left, and what you need to collect per day (assuming the rival stands still)
- a progress bar (your amount vs. the rival's)
- an animated glowing border in your accent color (two light streaks running around the HUD)

## Settings GUI
Open it with **Right Shift** (changeable under Options -> Controls -> Collection Tracker) or with `/ctrack`.

**Tracking tab**
- Collection: type a name (e.g. `timite`, `wheat`, `nether wart`); the GUI shows which Elite leaderboard it matched
- Rival: a player name, or the `#1` button to always chase whoever is first
- Deadline: `yyyy-MM-dd` or `dd.MM.yyyy` (counts as that day at 00:00 local time)
- Live item counting on/off and your own item names (empty = automatic)

**HUD tab**
- Size, background opacity, glow speed, position X/Y (sliders), toggles for HUD / progress bar / time line / glowing border, accent color (presets or hex)

Text fields are applied when you close the screen (Done / Esc); sliders and toggles apply instantly.
Buttons: **Refresh** (fetch now), **Reset** (back to defaults).
Everything is also stored in `.minecraft/config/collection-tracker.json`.

## Live item counting
Between API updates, every item you pick up (or mine) whose name matches the collection is counted +1
immediately. Automatic mode uses the collection name (for Timite: Youngite, Timite and Obsolite).
Enter your own item names in the GUI (comma separated). Limits: only items that land in your inventory are
seen (not sacks), worlds switches such as entering the Rift are ignored for 5 seconds, and moving items
in chests or the inventory is not counted. When Elite's data catches up, the bonus is reduced accordingly.

## Build with GitHub Actions
Push the repo, the *Build* workflow runs and the jar appears under Actions -> Artifacts.
Release: `git tag v1.0.0 && git push origin v1.0.0`.

## Notes
- Data comes from api.eliteskyblock.com. The leaderboard is found at runtime via /leaderboards.
- The command is `/ctrack` (not `/collection`) so it does not shadow Hypixel's own command.
