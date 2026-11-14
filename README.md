# SuperbVehicles

Data-driven vehicles for Paper 26.x: wheeled, tracked, boats, helicopters, planes and static emplacements with
mounted weapons, damage and armour, fuel, ammunition, seats, HUD and sounds. A vehicle is one YAML file plus one
Blockbench `.bbmodel` (and optional textures / sounds) - no Java. The plugin converts the models into a resource pack,
hosts it and sends it to players; vanilla clients only.

Clean room: no code, models, textures or sounds of any mod are used. The shipped models are procedurally generated
placeholders that follow the bone naming convention, so modellers can open them in Blockbench and replace the boxes.

## Install

1. Paper 26.2 or 26.3 (tested on 26.3), Java 21+.
2. Drop `SuperbVehicles-<version>.jar` into `plugins/`, start the server once. The plugin writes
   `plugins/SuperbVehicles/` with `config.yml`, `messages.yml`, `vehicles/*.yml` (the roster), `models/*.bbmodel`
   (placeholders), and builds `pack/SuperbVehicles-pack.zip` (+ `.sha1`, `pack-report.txt`).
3. The pack is served on the Minecraft port itself (`pack.host.mode: same-port`, nothing to open) and sent to every
   player on join, added to whatever packs the server already sends. Behind a Velocity/Bungee proxy use
   `pack.host.mode: self` (own port) or `url` (host the zip yourself), or `folder` to merge the assets into an existing
   pack folder that another plugin hosts.
4. `/sbv give truck` and right-click the ground.

Permissions: `superbvehicles.admin` (all commands), `superbvehicles.use.<id>` (enter), `superbvehicles.spawn.<id>`
(place the item). `superbvehicles.use.*` / `.spawn.*` default to true.

## Commands

`/sbv give <type> [player] [amount]`, `spawn <type> [x y z]`, `remove [radius]`, `removeall [type]`, `list`, `info`,
`repair [amount]`, `refuel [amount]`, `ammo`, `skin <name>`, `lock`, `unlock`, `tp <id>`, `seat <n>`, `reload`,
`pack [rebuild|send|url]`, `import <file.bbmodel>`, `item fuel|ammo|repair`,
`debug hitboxes|physics|seats|perf`. Aliases `/superbvehicles`, `/vehicle`. Tab completion everywhere.

## Controls

Right-click a vehicle to take the nearest free seat (the driver's first); right-click another seat box while seated
to switch, or `/sbv seat <n>`. Sneak leaves (ground vehicles below `seats.dismount-speed`, aircraft only when landed;
double-tap sneak in the air bails out with slow falling). Keys are configurable per class under `controls:`.

| Class | W / S | A / D | Space | Ctrl (sprint) | F (swap hands) | Mouse |
|---|---|---|---|---|---|---|
| wheeled | throttle / brake-reverse | steer | handbrake (drift) | fire (driver weapons) | engine on/off | - |
| tracked | throttle / brake-reverse | differential steer (neutral turn when stopped) | handbrake | fire | engine | - |
| boat | throttle / reverse | rudder | - | fire | engine | - |
| helicopter | collective up / down | pedals (yaw) | fire | countermeasure | engine (spool-up) | cyclic: look down = nose down and forward, look left/right = bank |
| plane | throttle up / down | rudder (nose wheel on the ground) | fire | airbrake | landing gear | pitch follows the look pitch, bank follows the look yaw and turns the aircraft; flaps are automatic below `flap-speed` with the gear down |
| static | - | - | fire | alt | - | aim |
| gunner seat | - | - | fire (hold for automatic) | alt fire | countermeasure | aim; hotbar 1-9 selects the seat's weapons |

Guided weapons: `wire` / `laser` steer toward the gunner's aim point, `lock` needs a lock (hold the target in the
lock cone for `lock-time`; the target sees a LOCK warning and may pop flares). The engine starts on the first
throttle press (`physics.auto-start`) and stops when the vehicle sits empty for a while.

## Adding a vehicle in 5 minutes

1. Copy `vehicles/sodayo.yml` to `vehicles/myjeep.yml`, change `id`, `display-name`, `model: myjeep`, numbers.
2. In Blockbench (Free / Generic model): build it in pixels, 16 px = 1 block, the origin (0,0,0) on the ground under
   the centre, the nose pointing NORTH (-Z). Name groups by the convention below; add locators `seat_driver`,
   `seat_<name>` for every seat in the YAML, `muzzle_1`... for weapons, `wheel_fl`... at wheel centres.
3. Save as `models/myjeep.bbmodel` (textures embedded - Blockbench does that on save).
4. `/sbv reload` (or `/sbv pack rebuild`): the pack is rebuilt in the background and re-sent. `pack-report.txt`
   lists every model's parts, locators and warnings.
5. `/sbv give myjeep`.

### Bone naming convention (overridable per vehicle under `bones:`)

| Group name | Behaviour |
|---|---|
| `root`, `body` | static hull (everything unnamed folds into the nearest named ancestor) |
| `wheel_*` (`wheel_fl`, `wheel_rr`, `wheel_m1`...) | NOT moved by the plugin (0.3.0): animate them in your `drive` / `reverse` / `left` / `right` clips; their locators still give the wheelbase |
| `track_l`, `track_r` | NOT moved by the plugin (0.3.0): animate them in the model |
| `turret` | yaws toward the gunner's aim; `gun` / `barrel` pitches (a weapon may name other bones with `turret-bone` / `gun-bone`) |
| `rotor_main`, `rotor_tail`, `propeller` | spin with the engine |
| `gear_*` | fold when the gear is raised (planes) |
| `flap_*`, `aileron_*`, `elevator`, `rudder` | control surfaces (`steer` is not moved since 0.3.0) |
| `dmg1_*`, `dmg2_*` | shown below 66% / 33% health; `intact_*` hidden when damaged; `wreck_*` shown when destroyed |
| `hitbox_*` | invisible box(es) defining the collision hull (else the model bounds) |
| locators `seat_*`, `muzzle_*`, `exhaust`, `wheel_*` | seats, muzzles, exhaust smoke, wheel centres |

Any group an animation moves also becomes a part of its own. Animations: a clip named like the physics state plays
in a loop - `idle`, `drive`, `reverse`, `left`, `right`, `start`, `hover`, `ascend`, `descend`, `fly`, `stall`,
`wreck` - or map names under `animations: {drive: my_clip}`. Linear, Catmull-Rom, step and bezier keyframes, numeric
values only (no Molang), Blockbench 4.x and 5 files. Rotations: Java cubes can only turn one axis at 22.5-degree steps,
so a cube rotated otherwise gets a display part of its own (exact, no snapping) - just more entities. Meshes are
replaced by their bounding boxes. Groups farther than ~24 px from their pivot are written smaller and scaled back up
(blurrier textures) - split big groups.

### Wheels and your own models (0.3.0)

The plugin does not spin wheels, steer them or move tracks any more, so your model's own animations decide how they
look. Name your clips after the driving states (`drive`, `reverse`, `left`, `right`, `reverse_left`, `reverse_right`,
`idle`) or map them in the YAML `animations:` table. Driving clips play at a rate that follows the speed: 1x at
`animation-speed` blocks/s (YAML, top level or under `physics:`, default half of `max-speed`). Key a wheel spin of 360
degrees over one second in Blockbench and set `animation-speed` to the wheel circumference in blocks
(2 x pi x radius) and the wheels roll without slipping. Groups that no animation moves are folded into the hull (fewer
entities).

### Importing models made for other tools (GeckoLib, Bedrock entity, modded entity)

Entity-format .bbmodel files work as they are. What the importer handles since 0.3.0:
* textures that are not embedded: put the PNG next to the .bbmodel (`models/<texture name>.png`,
  `models/<model>/<texture name>.png` or `models/textures/`); the file name from the texture's Blockbench path is
  tried too;
* faces with no texture (Blockbench shows nothing there) are left out instead of being drawn with the first texture;
* tall texture atlases (for example 256x512) are no longer mistaken for animated strips; an animated texture needs a
  `<texture>.png.mcmeta` next to the PNG;
* null objects work as locators (seats, muzzles);
* cubes rotated by angles a Java model cannot hold (anything but 0, 22.5, 45 on one axis) are exact but each distinct
  rotation becomes its own item display; the pack report warns above 48 parts per vehicle.

Check the vehicle's facing: entity models are usually modelled nose to the north (-Z), which is `model-yaw: 180`
(default). If it drives backwards, set `model-yaw: 0`. Size: 16 px = 1 block; use `scale:` if the model was made at a
different size. Respect the licence of models you did not make (attribution, non-commercial terms) and do not
redistribute them in public packs or repositories that the licence does not allow.

### Seats

Seats are placed by the model: a Blockbench locator (or a group) named like the seat's `locator` in the YAML (default
`seat_<name>`, for example `seat_driver`) is where the rider sits. Move the locator in Blockbench and rebuild the pack
(`/sbv reload`). On top of that, every seat has an `offset: [right, up, forward]` in blocks in the YAML, which the game
can set for all vehicles of a type at once:

```
/sbv seatoffset truck                      # list the seats, their locators and offsets
/sbv seatoffset truck driver 0 0.15 -0.1   # right / up / forward in blocks, applied live to every truck
/sbv seatoffset truck 2 add 0.05 0 0       # nudge seat number 2
/sbv seatoffset truck driver reset
```

The change is saved into `vehicles/<type>.yml` (the seat's `offset`). A seat whose locator is missing from the model is
flagged in the listing (it sits at the hull centre until you add the locator). `seats.rider-offset` in config.yml
moves every rider up or down if your client shows them sitting too high or low.

### Slabs, blocks and roads (0.4.0)

Cars, trucks and tanks climb anything up to their `step-height` (blocks): slabs, paths, carpets, stairs and with the
default 1.05 also full blocks, so slab roads that rise and fall drive smoothly. The body rests on the highest block
under it, goes down steps without leaving the ground, and each step is eased visually over a few ticks. Lower
`step-height` (0.6) if vehicles should not climb full blocks.

Boats run up slabs and shores too (`step-height`, default 0.6). Out of the water the hull slows at once
(`land-friction`, 9 blocks/s^2); for `land-grace` seconds (3) the engine still pushes (`land-push`, 13 blocks/s^2) but
only back toward the water it came from, so you can reverse or turn back. After that the boat is stranded until water
is under it again.

### Skins

```yaml
textures:
  desert: { body: truck_desert.png }   # texture name in the bbmodel -> file in models/skins/
  night: { "*": truck_night.png }       # replaces every texture
```
`/sbv skin desert` on a vehicle; also saved per vehicle.

### Sounds

Put `.ogg` files into `models/sounds/`; they become `superbvehicles:<name>` and can be used in `sounds:` of a vehicle
(`engine`, `start`, `stop`, `crash`, `horn`) or any weapon `sound` / `hit-sound` / `reload-sound`. The engine sound is
replayed every `sounds.engine-interval-ticks` with a pitch that follows the RPM, so use a short loopable clip.

### YAML reference

See the shipped `vehicles/*.yml` for every class. Physics keys are documented in the Javadoc of
`physics/WheeledPhysics`, `TrackedPhysics`, `BoatPhysics`, `HelicopterPhysics`, `PlanePhysics`; weapon keys in
`weapon/WeaponType`; the common keys (`class`, `model`, `model-yaw`, `scale`, `health`, `armor`, `fuel`, `seats`,
`weapons`, `bones`, `hide-groups`, `animations`, `sounds`, `textures`, `hitbox`, `hitzones`, `lockable`,
`invulnerable`, `wreck-seconds`, `explosion-power`) in `core/VehicleType`.

## Units (0.2.0)

Every number in `config.yml` and the vehicle YAMLs uses one unit system; the plugin converts to per-tick values in
exactly one place (`physics/Units`).

| Quantity | Unit | Example key |
|---|---|---|
| distance, step height, draft | blocks (1 block = 1 m) | `step-height: 0.6` |
| speed | blocks/second (x 3.6 = km/h) | `max-speed: 27` |
| acceleration, gravity | blocks/second^2 | `physics.gravity: 32` |
| mass | kilograms | `mass: 2200` |
| force | newtons (1 N on 1 kg = 1 block/s^2) | `engine-force: 10000` |
| angle | degrees | `steer-angle: 32`, `max-tilt: 22` |
| turn rates | degrees/second | `max-yaw-rate: 70` |
| grip | 1/second (sideways speed decays by e^-grip*t) | `lateral-grip: 8` |
| fuel | units; consumption in units/second at full load | `fuel.consumption: 0.3` |

Safety nets: NaN/Infinity guard, a per-tick position-delta cap, and an emergency freeze (logged once) when a vehicle
goes faster than 3x its `max-speed`. `physics.max-speed-cap` (80 blocks/s) bounds every vehicle.
`physics.speed-limit-by-chunk-loading` (default on) cuts the top speed to `unloaded-speed-factor` (25%) while the
chunks 2-4 s ahead are not loaded or still generating, so nobody outruns the server.

## Tuning guide

**Cars, trucks, tanks** (`class: wheeled` / `tracked`):

| Key | Meaning | Pickup | Truck | Tank |
|---|---|---|---|---|
| `mass` | kg | 2200 | 6500 | 22000 |
| `engine-force` | N at standstill; falls off as (1 - (v/max)^2), so the engine itself caps the speed | 10000 | 17500 | 47000 |
| `max-speed` / `max-reverse-speed` | blocks/s | 27 / 8 | 25 / 6 | 14 / 5 |
| `rolling-resistance` | coefficient (x 9.81 = blocks/s^2) | 0.015 | 0.018 | 0.03 |
| `drag-coefficient` | N per (blocks/s)^2 | 0.45 | 1.2 | 2.8 |
| `brake-force` / `handbrake-force` / `parking-brake-force` | N | 19000 / 22000 / mass x 6 | | |
| `lateral-grip` / `drift-grip` | 1/s; only the sideways component is damped | 8 / 1.2 | 7 / 1.0 | 12 |
| `steer-angle`, `steer-falloff`, `max-yaw-rate` | degrees, share lost at max speed, degrees/s | 32, 0.85, 60 | | |
| `neutral-turn-rate` | tracked: degrees/s on the spot | | | 32 |
| `wheelbase`, `wheel-radius` | blocks (default: from the wheel locators) | | | |
| `auto-start-engine` | engine starts when a driver sits down / presses W or S | true | | |
| `fuel.initial-fuel` | percent of the tank a new vehicle holds | 100 | | |

Rule of thumb: time to 80% of max speed is about `1.1 x max-speed x mass / engine-force` seconds. The defaults give a
pickup 6-8 s and a truck 10-12 s (both checked by unit tests). At startup and on `/sbv reload` every ground type is
simulated for 3 s of full throttle; a type below 20% of its max speed gets a console warning naming the cause.

**Helicopters**: `max-speed` (horizontal blocks/s - quadratic drag makes full cyclic level off exactly there),
`climb-rate`, `descent-rate`, `max-yaw-rate`, `max-tilt` (rotor disc, degrees), `tilt-rate-deg`, `vertical-accel`,
`spool-time`, `autorotation-descent`, `hover-settle-time` (seconds from full speed to hover after letting go), hard
clamps `max-horizontal-speed`, `max-climb-speed`, `max-descent-speed`, `auto-stabilise`.

**Planes**: `engine-force` (thrust, N; drag is sized so full throttle levels off at `max-speed`), `stall-speed`,
`min-takeoff-speed`, `lift`, `pitch-rate`, `roll-rate`, `max-yaw-rate` (degrees/s), `throttle-rate` (per second),
`alignment` (1/s), clamps `max-climb-speed`, `max-descent-speed`.

**Boats**: `engine-force`, `max-speed`, `max-reverse-speed`, `yaw-rate` (degrees/s), `draft`, `buoyancy` (1/s),
`water-drag` (N per (blocks/s)^2), `planing-speed`, `planing-pitch`, `rudder`, `lateral-grip`.

`/sbv debug physics` while riding toggles a per-tick line (every `debug.physics-interval-ticks`): driver seat,
raw input, engine, fuel, grounded, forward/lateral speed (blocks/s and km/h), engine/drag/rolling/brake
accelerations, collision result, the active clamp, and a plain-English "why not moving" naming the first failing link.

## Shader HUD

Drivers and pilots get textured gauges: a speedometer (needle + 3-digit readout in km/h or mph + unit), a fuel bar
or fuel arc (green / yellow / red, blinking warning under 15%, dark face and engine-off icon when empty) and, for
aircraft, a vertical-speed indicator, an artificial horizon (roll) with a pitch bar and a 4-digit altimeter.
Gunners and passengers keep the text line. Per player: `/sbv hud shader|text|off`, `/sbv hud units kmh|mph`
(saved in `data/hud-players.yml`; defaults `hud.mode`, `hud.units`).

**How it works.** The plugin sends the HUD as action-bar text in its own fonts (`superbvehicles:hud_<skin>`); every
glyph carries data in its text colour, and modified core text shaders (vanilla 26.3 `text.vsh` / `text.fsh` with an
added `IS_GUI`-only block) move each marked glyph to its place on screen, rotate needles, cut fill gauges and blink
icons with `GameTime`. Glyph shadows are fully transparent. Values are smoothed server-side and re-sent only when they
change (every `hud.update-ticks`, plus a keep-alive). Plugin messages to a rider are appended to the HUD line for
`hud.flash-ticks` instead of replacing it. The HUD clears on dismount, death, quit and destruction.

**Encoding** (`hud/HudEncoding`):

| Channel | Bits | Meaning |
|---|---|---|
| R | `11111101` (253) | marker |
| G | `101` + 5 bits | marker (top 3 bits) + element id 0..31 |
| B | 8 bits | value: fraction 0..1, or angle = value/255 x 360 - 180 degrees, or offset (0.5 = centre) |

Only text colours `#FDA000`-`#FDBFFF` are HUD data. Element ids and their kinds are listed in `hud/HudLayout`
(ground profile 0-12, aircraft profile 13-31).

**Placement.** `hud.profiles.ground` and `hud.profiles.aircraft`: `anchor` (bottom-right default, also bottom-left,
top-right, top-left, bottom-center), `offset` [x, y] in GUI pixels, `scale`, `opacity`. GUI pixels scale with the GUI
scale like the vanilla hotbar, so the layout holds at every resolution. Rebuild the pack after changing placement
(`/sbv reload`).

**Skins.** `hud-skin: military | civilian | aircraft` per vehicle; `gauge: {max-speed (blocks/s at full scale),
sweep (degrees), redline (0..1), fuel: bar | arc}`. Over the redline the needle turns red and jitters slightly.

**Delivery and fallback.** The HUD is a SECOND resource pack (`pack/SuperbVehicles-hud.zip`, pack format 97 only),
sent next to the models pack, so a client that cannot use it still has every vehicle model. A player only switches to
gauges after their client reports the HUD pack as successfully loaded; a failed, declined or discarded HUD pack puts
that player on the text HUD for the session. The shaders are written for Minecraft 26.3 and are only built when the
server runs 26.3 (`hud.shader.minecraft-version`); behind ViaVersion, players on other client versions should use
`/sbv hud text`. Shader mods (Iris/OptiFine-style) replace core shaders: those players see glyph images in the action
bar instead of gauges and should use `/sbv hud text`. In `url` mode also set `pack.host.hud-url`; `folder` mode never
merges the shaders into a foreign pack.

**Shader safety checklist** (checked at every pack build; any failure = no HUD pack, text HUD for everyone):

1. The shader files are the vanilla 26.3 files with additions only; every addition sits inside `#ifdef IS_GUI`, so
   world text (signs, name tags, text displays) runs the vanilla code.
2. Inside GUI text, only vertices whose colour is in the marker range are touched; every other GUI text (chat,
   tooltips, scoreboard, titles) runs the vanilla path.
3. 26.3 syntax: `#version 330`, `#include` (not `#moj_import`), `layout(location)` on every varying (ours is 4),
   the OIT structure untouched.
4. No colour the plugin sends (messages.yml, the 16 named colours) falls in the marker range.
5. `pack.mcmeta` valid with `min_format` >= 65; every font provider points to an existing texture.
6. Development check (not run on the server): both shaders compiled and linked for every define variant (`IS_GUI`,
   `IS_GUI IS_GRAYSCALE`, world, `IS_SEE_THROUGH`, `OIT_ALPHA_ONLY`) with the macOS OpenGL 4.1 driver, identically
   to the vanilla files.

**Replacing the art.** The textures are generated (`hud/HudTextures`); to use your own, unzip
`SuperbVehicles-hud.zip`, replace `assets/superbvehicles/textures/font/hud/<skin>/<glyph>.png`, keep the size ratio
of each element (`hud/HudLayout`: dial 64x64 GUI px, needle 6x30 with the pivot at (3, 27), fuel bar 14x64, fuel arc
48x48, icons 14x14, digits 8x11, unit 24x7, horizon 64x64, pitch bar 56x6; draw them at 4x), keep one pixel with alpha
1/255 in the top-right corner (it makes the client measure the full glyph width), and draw fills in white (the shader
tints them). The fuel arc runs from the bottom-left (-135 degrees, 0 = up) clockwise to the bottom-right (+135).
Host the edited zip with `pack.host.mode: url` / `hud-url`, or ask for a skin option in the generator.

## Architecture

```
bbmodel/   BbParser (Blockbench 4/5 -> BbModel), Track (keyframe sampling), BoneRole (naming convention), Rig (runtime bone matrices)
pack/      CompositeBuilder (bbmodel -> one item model per animatable bone), PackBuilder (zip, icons, sounds.json, HUD font),
           PlaceholderModels (procedural .bbmodel templates), PackHost (same-port | self | url | folder delivery), Mat
compat/    SamePortHttp - the only reflection/netty code (serves the zip on the Minecraft port)
render/    Pose (vehicle frame), VehicleRenderer (ItemDisplay parts riding a marker stand, changed transforms only),
           AnimationPlayer (state clips, crossfade, one-shots), Procedural (wheels, turret, rotors, gear, flaps, damage states)
physics/   Obb + BlockCollider (own hull-vs-block sweep with stepping), Wheeled/Tracked/Boat/Helicopter/Plane/Static models
core/      VehicleType (YAML), Vehicle (instance), VehicleManager (tick loop, lookups, chunks), Persistence, Items, Messages
seat/      SeatManager (enter/exit/switch, dismount rules, bail-out), InputListener (PlayerInputEvent, hotbar, F, Q, clicks)
weapon/    WeaponType, MountedWeapon (aim, ammo, reload, lock), Projectile (simulated rounds, guidance), ProjectileManager
damage/    DamageModel (zones, armour, fire, wreck), DamageListener (melee, arrows, explosions)
hud/       HudManager (action bar, boss bar, glyph bars, lock warning)
command/   SbvCommand (Brigadier)
api/       SuperbVehiclesAPI + events (Spawn, Enter, Exit, Damage, Destroy, WeaponFire, MissileLock)
```

Paper APIs used (all present in 26.2 and 26.3): `ItemDisplay`/`Interaction`/`ArmorStand` entities,
`PlayerInputEvent`, `ItemMeta#setItemModel` (the `item_model` component), `Player#sendResourcePacks`, Brigadier via
`LifecycleEvents.COMMANDS`, global-region and async schedulers, `TeleportFlag.EntityState.RETAIN_PASSENGERS`.

Rendering: the root is an invisible marker armour stand at the vehicle position; every rig part is an `ItemDisplay`
riding it whose transformation is `R_vehicle * M_bone` (translation in blocks, rotation as a quaternion, scale per
axis), re-sent only when it changed, interpolated by the client over `render.interpolation-ticks`. Seats are marker
stands teleported each tick (riders sit on them) with an `Interaction` click box each; one more `Interaction` covers
the hull for clicks and vanilla hits. All entities are non-persistent; `data/vehicles.yml` restores vehicles.

Performance: one vehicle costs about 0.01 ms per tick on the test machine (`/sbv debug perf`); the pack build runs
off the main thread; projectiles are capped (`weapons.max-projectiles`).

## Build

Maven (used here, offline-capable): `mvn package` -> `target/SuperbVehicles-<version>.jar`. The Gradle Kotlin DSL
build (`build.gradle.kts`) mirrors the pom but has not been run on the author's machine. `GenerateRoster` (test
sources) regenerates the shipped placeholder models and `defaults-index.txt`.

## Troubleshooting

* **Purple/black or missing bodies**: the pack is not applied. Check `/sbv pack` (sha1, host status), the client log
  for "Error reading pack metadata" (pack format below 65 is rejected by 26.x clients - never lower `pack.min-format`),
  and that the server address players use can reach the pack (`pack.host.address`). Behind a proxy use `self` or `url`.
* **"no models/<name>.bbmodel - a placeholder was generated"**: the YAML `model:` does not match a file in `models/`
  (case matters).
* **A model looks scrambled** (0.1.0 / 0.2.0): every part was drawn turned 180 degrees about its own centre because
  item displays turn their item half a turn on the client; fixed in 0.3.0 - update the jar.
* **Parts float / drive backwards**: `model-yaw` (180 = nose at Blockbench north) or the group pivots; see
  `pack-report.txt` warnings and `/sbv debug hitboxes|seats`.
* **Riders sit too high/low**: `seats.rider-offset`; parts offset vertically: `render.passenger-offset`.
* **A vehicle does not move**: `/sbv debug physics` while sitting in it prints the first failing link (no driver
  seat, no input, out of fuel, engine off, no ground contact, engine too weak, resistance beats thrust, blocked) and
  the console self-check warns about types that cannot reach 20% of their max speed.
* **Gauges do not show / glyph images in the action bar**: the HUD pack did not load for that client (shader mod,
  other client version, declined pack) - `/sbv hud` shows the state; `/sbv hud text` switches to the text HUD.
* **Vehicle not restored after restart**: `data/vehicles.yml` is saved every `persistence.save-interval-seconds` and on
  stop; a killed server loses the last interval.

## Known limitations

* Blockbench meshes become boxes; textures of groups far from their pivot are written at reduced size.
* No per-player view models or first-person effects; the hand-held item is the vanilla one.
* Same-port pack hosting does not work behind Velocity/Bungee (use `self`/`url`/`folder`).
* Folia: schedulers are the region-aware ones, but the tick loop moves entities across regions and was not tested on Folia.
* Rendering, seat placement, driving feel and all HUD/visuals were verified only as far as noted in the delivery
  message; everything else was verified headless (boot, commands, persistence, pack build, unit tests).
