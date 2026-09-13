# DontDisconnectMe

A Velocity plugin that keeps players on your network when a backend server
restarts, crashes or drops them — instead of dumping them back at the server
list.

**Players do not go anywhere.** When their server dies they are held on the
proxy itself: their client is never told, so it keeps the world it already has
on screen — the terrain, their inventory, everything exactly where it was. They
simply cannot interact, because there is no longer a server behind the proxy to
respond to them. Meanwhile they get the kick reason in the action bar, a live
"unreachable for 01:23" timer with Otherside playing, and Lava Chicken while
they wait their turn to go back. The first thing their client reloads is the
real server, once it is healthy again.

No limbo server, no backend plugin, nothing else to run.

Every message, sound, title, boss bar and timing is configurable, globally and
per server.

---

## What actually happens

| Phase | When | Default behaviour |
|---|---|---|
| `kicked` | the instant we take the kick over | player is held in place, action bar with the kick reason, enderman-teleport blip, one immediate retry |
| `waiting` | the server is unreachable | `Server Restarting` title, live downtime clock, boss bar, **Otherside** on loop, a retry blip every 4s |
| `reconnecting` | the server is healthy again | `Reconnecting` title, queue position, **Lava Chicken** (Hyper Potions) |
| `success` | they made it back | all plugin music stopped, welcome-back message, level-up sound |
| `failed` | we gave up | failure title and message; kick or stay, your choice |

### How it avoids reconnect spam

This is the part that matters when a server is genuinely restarting:

* **One** instant retry for an ordinary blip (a timeout, a dropped connection).
  If that fails, the plugin stops trying to log in.
* From then on it **pings** the server every 2s instead of attempting logins.
  Pings are cheap and cannot interfere with a booting server.
* Kick reasons that look like a restart (`restarting`, `server closed`,
  `maintenance`, …) skip the instant retry entirely.
* A real login is only attempted once the health check says the server is up
  *and* it has been up for `queue.start-delay-ms` (3s by default).
* `reconnect.retry.min-interval-ms` is a hard floor no config can go below.

### How it avoids slamming a freshly booted server

Players go into a per-server queue ordered by who was kicked first, and are
released **2 every 500ms** by default. Priority permissions jump the line; a
bypass permission skips it. A player whose attempt fails keeps their place
rather than going to the back.

---

## Requirements

* **Velocity 3.4.0+** and **Java 17+**
* Nothing else. Holding players on the proxy needs no limbo server and no
  backend-side plugin.

### How players are held

`hold.mode` picks between two approaches:

**`FREEZE` (default)** — the player stays on the proxy. Nothing is sent to their
client, so it keeps rendering the world it already has; when their server comes
back they are connected straight to it and that is the only reload they see.

Velocity's public API has no way to say *"keep this player attached to nothing"*,
so this is the one part of the plugin that reaches into proxy internals: a netty
handler on the player's channel drops the disconnect packet Velocity tries to
send and refuses the close that follows, and keep-alives and sounds are written
to the client directly. Everything else — chat, action bar, title, boss bar —
uses the ordinary public API.

That makes `FREEZE` the part most likely to need attention after a Velocity
update, so it fails safe: all of it is resolved once at startup, and if anything
has moved the plugin logs a warning and falls back to `SERVER` automatically.
Check your log on startup — it says which one you got:

```
[dontdisconnectme]: Players will be held on the proxy itself; no limbo server needed.
```

**`SERVER`** — the classic approach: move the player to a limbo or lobby server
from `hold.servers` and reconnect them from there. Their client reloads into the
limbo world and then again into the real one. Use this if you would rather not
depend on proxy internals, or if you want players to have something to do while
they wait. A tiny limbo works best
([NanoLimbo](https://github.com/BoomerCraft/NanoLimbo),
[LimboAPI](https://github.com/Elytrium/LimboAPI)), but a lobby with a small void
world is fine.

It is worth listing a server in `hold.servers` either way: it is the fallback if
`FREEZE` is unavailable, and where players are put down if the plugin eventually
gives up on them.

### One backend setting you probably need to change

Paper/Spigot rate-limit connections **per IP address**, and behind a proxy every
player shares the proxy's IP. With the default `connection-throttle: 4000`, the
second player let back in each batch is rejected with *"You are logging in too
fast, try again later."*

In each backend's `bukkit.yml`:

```yaml
settings:
  connection-throttle: -1
```

This is standard advice for any proxied network — the proxy is the thing that
should be rate-limiting logins, not the backend. If you would rather not change
it, set `queue.batch-size: 1` and `queue.interval-ms: 5000` instead.

Velocity's own `login-ratelimit` in `velocity.toml` is also per-IP, but that one
applies to real players connecting from different addresses, so it normally
needs no change.

---

## Install

1. Drop `DontDisconnectMe-x.y.z.jar` into your proxy's `plugins/` folder.
2. Start the proxy once to generate `plugins/dontdisconnectme/config.yml`.
3. Set `hold.servers` to your limbo/lobby server names.
4. `/ddm reload`.

Grab a jar from the [Releases](../../releases) page, from the **Build** workflow
artifacts on any commit, or build it yourself:

```bash
./gradlew build      # -> build/libs/DontDisconnectMe-1.0.0.jar
```

---

## Commands

| Command | What it does |
|---|---|
| `/ddm reload` | re-read `config.yml` |
| `/ddm status` | every watched server, up/down, for how long, who is waiting |
| `/ddm queue [server]` | who is in the re-entry queue |
| `/ddm cancel <player>` | stop reconnecting someone |
| `/ddm simulate <player> [server]` | walk a player through every screen without touching a server |

`/ddm simulate` is the fastest way to tune your messages — it plays the whole
flow (kick → restart screen → reconnecting → welcome back, music included) on a
fixed script and never moves the player anywhere.

### Permissions

| Node | Grants |
|---|---|
| `dontdisconnectme.admin` | the `/ddm` command |
| `dontdisconnectme.queue.bypass` | skip the re-entry queue entirely |
| `dontdisconnectme.queue.priority.high` / `.medium` / `.low` | jump the queue, in that order |

All configurable — the permission names above are just the defaults.

---

## Configuration

`config.yml` is heavily commented; the highlights:

* **`filters`** — regexes deciding which kicks to take over. Bans, whitelist
  kicks and "kicked by an operator" pass straight through by default; anything
  matching `restart-reasons` skips the instant retry. `only-reasons` turns it
  into an allow-list if you want to handle timeouts and nothing else.
* **`hold`** — `mode` (`FREEZE` or `SERVER`), the keep-alive interval for held
  players, and the fallback servers.
* **`watcher`** — how aggressively servers are health-checked, and the
  thresholds that decide up/down. `success-threshold: 2` exists because a server
  answers pings before it accepts logins.
* **`reconnect`** — the instant retry, the patient loop, optional exponential
  backoff, and when to give up.
* **`queue`** — batch size, interval, start delay, priority permissions.
* **`phases`** — every screen and sound, per phase (see below).
* **`servers`** — per-server overrides, deep-merged over everything above.

### Per-server overrides

Anything from the sections above can be repeated under a server name; only list
what you want to change and the rest is inherited:

```yaml
servers:
  survival:
    queue:
      batch-size: 1
      start-delay-ms: 10000     # it takes a while to load chunks
    reconnect:
      retry:
        max-duration-seconds: 1800
    phases:
      waiting:
        title:
          title: "<red>Survival is restarting"

  creative:
    reconnect:
      enabled: false            # never reconnect anyone to creative
```

### Sounds and music discs

Each phase takes a list of sounds:

```yaml
sounds:
  - key: "minecraft:music_disc.otherside"
    source: RECORD
    volume: 1.0
    pitch: 1.0
    delay-ms: 0
    loop-seconds: 195      # replay every 195s so the track never stops
    stop-on-exit: true     # stop it when the phase ends
    min-protocol: 757      # 1.18+, older clients just hear nothing
    max-protocol: 0
```

`min-protocol` / `max-protocol` gate a sound to a client version range, which is
how the default config ships **Lava Chicken** (1.21.5+, protocol 770) with a
plain beacon chime as the fallback for older clients. Handy numbers: 1.18 = 757,
1.20.1 = 763, 1.21 = 767, 1.21.4 = 769, 1.21.5 = 770, 1.21.8 = 772.

Anything the plugin started with `stop-on-exit: true` is stopped when the phase
ends, and `reconnect.stop-music-on-success` stops all of it the moment the
player is back on their server.

### Placeholders

Every message is [MiniMessage](https://docs.advntr.dev/minimessage/format.html)
and accepts:

| | |
|---|---|
| `<player>` `<uuid>` | who |
| `<server>` `<hold_server>` | where (`<hold_server>` is empty while held on the proxy) |
| `<reason>` `<reason_plain>` | why they were kicked (formatted / plain) |
| `<phase>` | current phase |
| `<attempt>` `<max_attempts>` | how many tries so far |
| `<downtime>` `<downtime_clock>` `<downtime_seconds>` | how long the server has been unreachable |
| `<elapsed>` `<elapsed_clock>` `<elapsed_seconds>` | how long this player has been waiting |
| `<next_retry>` | seconds until the next attempt |
| `<queue_position>` `<queue_size>` `<queue_eta>` | place in line |

Durations render as `1m 23s`, `01:23` or `1 minute, 23 seconds` depending on
`general.time-format.style`.

---

## Building

```bash
./gradlew build     # compiles, runs the tests, produces the jar
./gradlew test      # tests only
```

Every push and pull request is built and tested by GitHub Actions, which uploads
the jar as a build artifact. Pushing a `v*` tag publishes a release with the jar
attached.

---

## Troubleshooting

**"Cannot hold players on the proxy on this Velocity build"** — the internals
`FREEZE` relies on have moved, most likely after a Velocity update. The plugin
has fallen back to `SERVER` mode, so make sure `hold.servers` names a real
server; please open an issue with the Velocity version from your log.

**"None of the servers in `hold.servers` exist in velocity.toml"** — the names
must match your `[servers]` section in `velocity.toml` exactly.

**A held player cannot be kicked** — while someone is held, the plugin is
refusing the disconnects Velocity sends them, so an admin kick will not land
either. Run `/ddm cancel <player>` first, which stands them down, or wait for
`reconnect.retry.max-duration-seconds` to expire.

**Players get kicked properly instead of reconnected** — check the kick reason
against `filters.ignored-reasons`; those patterns match anywhere in the message,
so a broad one like `.*ban.*` will also swallow "banner".

**"You are logging in too fast"** — see the `connection-throttle` note above.

**No music** — music discs only exist from certain versions (Otherside 1.18+,
Lava Chicken 1.21.5+) and are gated by `min-protocol`. Turn on `general.debug`
and the console logs every sound it plays, for whom, and on which server.

**Nothing at all happens** — turn on `general.debug`. Every ping, state change,
attempt and queue release is logged.
