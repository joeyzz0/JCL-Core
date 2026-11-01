# JCL Core

The all-in-one server mod for Joey's Cobblemon League (NeoForge 1.21.1, Cobblemon 1.8).
Mod ID stays `jcl_gts` so existing GTS listings and claims keep working.

| Feature | Commands | Config |
|---|---|---|
| GTS (buy, sell, auction) | `/gts`, `/gts claims`, `/gts admin ...` | `config/jcl_gts.json` |
| Wonder Trade | `/wondertrade`, `/wondertrade <slot>` | `config/jcl_wondertrade.json` |
| Shop (CobbleDollars) | `/shop`, `/shop reload` | `config/jcl_shop.json` |
| Crates and keys | `/crate preview <tier>`, `/crate merge`, admin: `/crate give/set/remove/reload/pity` | `config/jcl_crates.json` |
| Tab list | automatic | `config/jcl_tablist.json` |
| Chat announcements | automatic | `config/jcl_announcer.json` |
| Spawn alerts | automatic | `config/jcl_spawnalerts.json` |

Configs are created with defaults on first start. World data is saved in `<world>/data/jcl_gts/`.

## Building
Push to GitHub. The workflow in `.github/workflows/build.yml` builds the mod; download the
**jcl-core-jar** artifact from the Actions tab. The jar goes in the server's `mods` folder and in the client pack
(the mod adds key items, so clients need it).

## Layout
Every file sits in the top folder (no subfolders except `.github`). `build.gradle` copies the key textures (`*_key.png`), models (`*_key.json`), `en_us.json`, and the default `crates.json` / `shop.json` into the right places inside the jar.
