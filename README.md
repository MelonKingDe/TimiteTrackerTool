# Timite Tracker (Fabric 26.2)

Client-side mod for Hypixel Skyblock. Shows a permanent HUD (top-left) with:
- your Timite collection and rank
- the collection of dinofeet_ (or the current #1 if dinofeet_ is not in the top 100)
- the gap, and how much you need to collect per day to be #1 before 2027 (assuming the #1 stands still)

Live counting: every Youngite, Timite or Obsolite you pick up (or mine) is added to your count immediately
(+1 each). When Elite's data catches up, the live bonus is reduced accordingly. Switching worlds (e.g. entering or leaving the Rift, which has its own inventory) is ignored for 5 seconds. Moving items in chests or
your inventory screen is not counted.

## Config / moving the HUD
Config file: `.minecraft/config/timite-tracker.json` (x, y, scale, backgroundOpacity, visible, showProgressBar, showUpdated, accentColor).

| Command | Effect |
|---|---|
| `/timite` | refresh now |
| `/timite pos <x> <y>` | move the HUD (GUI pixels from top-left) |
| `/timite scale <0.5-3>` | resize the HUD (1.0 = default) |
| `/timite bg <0-100>` | background opacity |
| `/timite toggle` | show / hide |
| `/timite resetlive` | clear the live pickup bonus (+N) |
| `/timite reload` | reload the config file after editing it |
| `/timite reset` | back to defaults |

The HUD refreshes automatically every 5 minutes.

## Build with GitHub Actions
Push the repo, the *Build* workflow runs and the jar appears under Actions -> Artifacts.
Release: `git tag v1.0.0 && git push origin v1.0.0`.

## Notes
- Data comes from api.eliteskyblock.com (the Timite leaderboard id is found at runtime via /leaderboards).
