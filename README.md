# rift-border-api

[![build](https://github.com/natesoftware/rift-border-api/actions/workflows/build.yml/badge.svg)](https://github.com/natesoftware/rift-border-api/actions/workflows/build.yml)

A volumetric circular border for Paper 1.21.11 servers. Renders a shrinkable
cylinder, animates shape transitions, and damages players who stray outside the
radius or beyond an optional ceiling / floor.

This repository is the API you build against. On a server it runs inside the
**RiftBorder** plugin, which provides these classes to every plugin that uses
them, delivers the resource pack that draws the solid shader wall, and gives
players `/border` to choose their wall style. RiftBorder is not published:
message `Nateiwnl` on Discord for the jar.

## Features

- Smooth shrink and re-centre animations driven by a single `moveTo` call
- Optional volumetric ceiling and floor (not just a 2D ring)
- Optional multi-phase controller with pause / resume / sync-to-timer
- Pluggable choice of where each phase shrinks to
- Per-player damage with grace period, a warning subtitle, and re-entry sound
- A solid shader wall or a particle wall per player, chosen for you by the plugin
- Particle-ring preview of the next phase's target
- `BorderTheme` for how it looks and sounds, `BorderEvents` for reacting to what happens

## Requirements

- Paper (or compatible fork) 1.21.11+
- Java 21
- The RiftBorder plugin installed on the server

## Setup

1. Install `RiftBorder.jar` in the server's `plugins/` folder. Its
   `config.yml` chooses how players get the pack (see [Rendering](#rendering)).
2. Compile against the API without bundling it:

```kotlin
repositories {
    maven("https://jitpack.io")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.natesoftware:rift-border-api:v4.1.1")
}
```

3. Depend on the plugin in your `plugin.yml`:

```yaml
depend: [RiftBorder]
```

**Do not shade or bundle the library.** RiftBorder provides it at runtime, so
every plugin on the server shares one copy of these classes. That shared copy
is how your borders find the pack and each player's wall style with no wiring
at all; a bundled copy would be cut off from it and show everyone particles.

Package `com.natesoftware.riftborder.api`. To build against local changes
instead, clone this repo beside your plugin, add
`includeBuild("../rift-border-api")` to `settings.gradle.kts`, and use
`compileOnly("com.natesoftware:rift-border-api:4.1.1")`.

## Usage

```java
import com.natesoftware.riftborder.api.GameBorder;

GameBorder border = new GameBorder(plugin, world, centerX, centerY, centerZ)
    .withParticipants(() -> alivePlayerUuids);      // Supplier<Set<UUID>>
border.spawn(200);
```

That is a working border with the library's look and sound. `withParticipants`
is optional, but without it **every survival or adventure player in the world**
is subject to the border. [Theme and events](#theme-and-events) covers making it
your own.

From here there are two ways to drive it. Pick one.

### Manual

You decide where and when:

```java
border.moveTo(50, 50, 100, 20 * 30);        // shrink to (50,50) r=100 over 30s
border.onShrinkComplete(() -> border.moveTo(80, 10, 60, 20 * 20));
border.setDamagePerSecond(3.0);
```

`moveTo` has overloads that also take a ceiling Y and a floor Y. `setPosition`
snaps with no animation. `pauseShrinking` / `resumeShrinking` freeze and
continue an in-flight shrink.

### Phase controller

Or hand over a schedule and let the library drive:

```java
import com.natesoftware.riftborder.api.BorderPhase;
import com.natesoftware.riftborder.api.BorderPhaseController;
import com.natesoftware.riftborder.api.NextBorderIndicator;

List<BorderPhase> phases = List.of(
    new BorderPhase(60, 30, 100, 2.0),  // wait 60s, shrink 30s to r=100, 2 dmg/s
    new BorderPhase(30, 30, 50,  3.0),
    new BorderPhase(15, 15, 0,   5.0));

BorderPhaseController controller = new BorderPhaseController(plugin, border, phases);
controller.start();   // runs for exactly BorderPhase.totalSeconds(phases)

// Optional: pulse a faint ring where the next phase will land
NextBorderIndicator indicator = new NextBorderIndicator(controller);
indicator.start();
```

The controller takes the map centre and starting radius from the border, so
spawn the border before `start()`. `start()` runs the controller's clock for
exactly the schedule's length, `BorderPhase.totalSeconds(phases)` (every
phase's wait plus its shrink). Give your own round timer that same number and
the two stay one to one: every later `setRemainingSeconds` lines them up again.

Two longer forms exist for other cases. `start(seconds)` runs a longer clock,
for a round that outlasts the border's phases. The six-argument constructor
takes a map centre other than the border's own, and a starting radius.

`BorderPhase` has longer constructors that add a ceiling Y and a floor Y for
that phase. Do not also call `moveTo` yourself while a controller is running;
the controller owns the shape.

If your game has its own clock, keep the controller aligned with it:

```java
controller.pause();
controller.resume();
controller.setRemainingSeconds(secondsLeft);   // re-sync after a host adds or removes time
controller.onAllPhasesComplete(() -> ...);     // the last phase has landed
int hud = controller.getSubPhaseRemaining();   // seconds left in the current wait or shrink
```

### Shrink targets

Where each phase shrinks *to* is a `ShrinkTargetSelector`. Two ship in the
box: `RANDOM_INSIDE` (the default, a uniformly random point inside the current
zone) and `FIXED_CENTER` (always toward the map centre). Supply your own to
express anything else. It returns a `BorderPoint`; the controller clamps
whatever you return so the new circle always fits inside the previous one,
pulling an out-of-range point back along its ray.

```java
controller
    .withTargetSelector(ctx -> new BorderPoint(peakX, peakZ))   // e.g. highest point
    .withRandom(new Random(matchSeed));                         // reproducible zones, optional
```

Targets are resolved as each phase's wait begins, so a selector that reads
player positions sees them as they are then, and the indicator can preview the
result during the wait.

### Teardown

Always tear down when the match ends and on plugin disable:

```java
border.remove();      // also stops any controller and indicator attached to it
```

`remove()` takes every viewer's wall display down. The displays are never saved
with the world and the border loads no chunks of its own, so a server that
stops without it leaves nothing behind for the next start.

### Theme and events

Two optional interfaces, both all-defaults, so implement only what you want to
change:

- **`BorderTheme`**: how the border looks and sounds. Wall colour, colour while
  shrinking, warning subtitle, the sounds for crossing out and staying out.
- **`BorderEvents`**: what happens. A phase or shrink starting, a player
  crossing out or coming back. Every event does nothing by default.

```java
GameBorder border = new GameBorder(plugin, world, x, y, z)
    .withTheme(new BorderTheme() {
        @Override
        public Color shrinkColor() {
            return Color.RED;                     // red while it moves
        }
    })
    .withEvents(new BorderEvents() {
        @Override
        public void onPhaseStart(int phase, int total, int waitSeconds) {
            Bukkit.broadcast(Component.text("Phase " + phase + "/" + total));
        }
    });
```

One class may implement both. What players get out of the box:

| | Default |
| --- | --- |
| Damage outside the border | 2.0 / s, after a 1 s grace, with the vanilla hurt flash and sound |
| Warning subtitle | `ʙᴏʀᴅᴇʀ ᴡᴀʀɴɪɴɢ` in red small caps (`DEFAULT_WARNING_SUBTITLE`), flashed once when a player crosses out: one second on screen, then a half-second fade. It is not re-sent while they stay out or cleared when they come back. Return null from `warningSubtitle()` for none |
| Sound on crossing out | `minecraft:block.note_block.bass` |
| Sound while still outside | the crossing-out sound again, every 5 s |
| Wall colour, both wall styles | aqua `#55FFFF` (`wallColor()`) |
| Colour while shrinking or moving | the wall colour (`shrinkColor()` returns null) |
| Creative / spectator | ignored |

Border damage writes health directly. Non-lethal ticks fire no
`EntityDamageEvent`, so armour, Resistance and other plugins' damage listeners
do not see them; only the killing tick goes through `Player.damage` with the
`OUTSIDE_BORDER` damage type. If you need event-visible damage, set the rate
to 0 (the warnings and sounds keep working) and deal it yourself from
`onWarningShown` / `onWarningCleared`, which tell you exactly who is outside
and when they return.

## Wall styles

Each player sees one of two wall styles (`WallStyle`):

| Style | What it looks like |
| --- | --- |
| `SHADER` | A solid cylinder wall, drawn by the rift-border resource pack |
| `PARTICLE` | A dust wall on the arc nearest the player, density scaled to the current radius |

**You configure nothing.** The RiftBorder plugin decides per player: anyone
whose client loaded the pack sees the shader wall, anyone who declined it or
chose `/border particle` sees particles. Both take their colour from
`wallColor()` on your theme (aqua `#55FFFF` by default), and both follow a
change within a second while the border is live. Return a `shrinkColor()` as
well and the wall switches to it while the border is moving, and back once it
lands.

The server owner chooses how the pack reaches players in RiftBorder's
`config.yml`: served from the server itself, downloaded from a URL, or carried
inside another plugin's pack.

Two overrides exist for special cases. `withShaderWall()` forces the shader
wall on, and `withWallStyleResolver(uuid -> ...)` decides every player's style
for one border. `WallStyles.get()` is the plugin's side: whether the shader
wall is available, and each player's chosen style, for a settings menu of your
own.

The shader wall is mounted on one invisible display per viewer, spawned where
they stand and shown to them alone, since any single display draws the whole
cylinder. A player who moves more than 32 blocks from theirs gets a fresh one
where they stand. There is no radius cap and no chunk loading.
`withAnchorY(y)` sets the height the displays sit at; it defaults to the
world's max build height, one block above the highest. Keep it outside the build
height: vanilla clients only draw an entity inside it while its chunk section is
on screen, so a display up in empty sky would vanish for anyone not on Sodium.
`withGrid` sized the old fixed grid of displays and does nothing since 4.1.0.

### Pack contract

The wall is a `Material.PAPER` `ItemStack` with the item model
`rift-border:border`, dyed with the theme's `wallColor()`, carried by
an `ItemDisplay` kept near each viewer. The pack's item definition tints
from that dyed colour, the model is expanded into a cylinder by a core-shader
override that colours the wall from the tint, and the border's
geometry reaches the shader through the display's scale: **X and Y carry the
live radius, Z carries the pattern-anchor radius** (the transition target while
shrinking, so the pattern does not slide mid-shrink).

The pack that implements this contract ships inside RiftBorder. To draw a
wall of your own instead, build a pack against the contract above under the
same `rift-border:border` key.

## Example

[`examples/ExamplePlugin.java`](examples/ExamplePlugin.java) is a complete
plugin assembled from the snippets above: it spawns a border around the first
world on enable, drives it through three phases, previews each target, and
tears everything down on disable. Drop it into a project set up per
[Setup](#setup) with the `plugin.yml` beside it.

## Development

```bash
./gradlew build      # compiles, runs the tests, builds the jar, sources jar and javadoc jar
./gradlew test       # the suite alone - the schedule, animator, damage tracker and selectors run against a fake scheduler, no server needed
./gradlew javadoc    # API docs into build/docs/javadoc
```

The public types carry Javadoc, also hosted per release at
`https://javadoc.jitpack.io/com/github/natesoftware/rift-border-api/<tag>/javadoc/`;
the internals carry `//` notes. CI runs `build` on every push and pull request.

## Upgrading from 3.x

4.0.0 turns the persistent warning title into a brief subtitle.

| 3.x | 4.0.0 |
| --- | --- |
| `warningTitle()`, `DEFAULT_WARNING_TITLE` | `warningSubtitle()`, `DEFAULT_WARNING_SUBTITLE` |
| a title that stays on screen while the player is outside, re-sent every second and cleared when they return | a subtitle shown once per crossing for one second, then a half-second fade, never re-sent or cleared |

## Upgrading from 2.x

3.0.0 renames the API so every name says what it is. The behaviour is unchanged.

| 2.x | 3.0.0 |
| --- | --- |
| `com.github.natesoftware:rift-border` | `com.github.natesoftware:rift-border-api` |
| package `com.natesoftware.riftborder` | `com.natesoftware.riftborder.api` |
| `BorderCallbacks` + `withCallbacks` (required) | `BorderTheme` + `withTheme`, `BorderEvents` + `withEvents` (both optional) |
| `phaseStarted`, `shrinkStarted` | `onPhaseStart`, `onShrinkStart` |
| `enterSoundKey`, `enterLongSoundKey` | `enterSound`, `enterLongSound` |
| `BorderRenderMode` | `WallStyle` |
| `BorderEnvironment` | `WallStyles` |
| `renderModeFor`, `preference`, `setPreference` | `styleFor`, `chosenStyle`, `setChosenStyle` |
| `withRenderModeResolver` | `withWallStyleResolver` |
| `setRemainingTime` | `setRemainingSeconds` |
| `setOnAllPhasesComplete` | `onAllPhasesComplete` (now chainable) |

## Licence

MIT. See [LICENSE](LICENSE).
