# Changelog

## 0.8.0 - 2026-11-18

Not verified on a real client yet: how the new driving looks and feels, the exit positions, the prompts.

### Added
- **Rigid-body ground model** for cars, trucks and tanks (`physics.ground-model: rigid`, the default; `classic` brings
  the 0.7 controller back, also per vehicle with `physics.ground-model`). Wheel force from a torque curve, limited by
  the grip of the block under the wheels; air drag, rolling resistance; gravity along the slope, so vehicles gain
  speed downhill and lose it uphill; spring-and-damper suspension on four or six contact points; the body's pitch and
  roll come from the plane through those points.
- **Step classes from real block shapes.** Up to `free-step` (0.55: slabs, stairs, snow, trapdoors) the wheels roll
  up without losing speed. A full block (up to `step-height`) is climbed only with enough speed
  (`full-block-climb-threshold`) or drive force (`full-block-climb-traction`), and costs speed. Anything taller, or a
  ledge under a ceiling too low for the lifted body, is a hard hit with a rebound and a spin.
- **Surfaces.** Ice, sand, gravel, mud, snow and dirt change grip and rolling resistance (`physics.surfaces`).
- **Sleep.** Empty vehicles at rest are not ticked at all. They wake when entered, clicked, damaged, rammed, or when
  the ground under them changes. `/sbv debug perf` shows awake / asleep and why each awake vehicle is awake.
- **One bundle per tick.** All movement and part updates of vehicle entities reach a client as one bundle packet.
  Part interpolation is 2 ticks by default (`network.interpolation-ticks`, `network.teleport-ticks`).
- **Safe exit.** Getting out puts the rider on the first safe place: own-side door, other door, rear, roof, above.
  Safe = room for a standing player, floor or water within 3 blocks, no lava / fire / magma / cactus / berry bush
  (`seats.dismount`).
- Action-bar prompts: "Right-click to enter" when looking at a vehicle, "Sneak to exit" when seated (`seats.prompts`).

### Changed
- Gravity for ground vehicles on the rigid model is 9.81 blocks/s^2 (vehicle YAML `physics.gravity`), not 32.
- Without `drag-area` in a vehicle file, drag is solved so that top speed is still `max-speed`.
- `render.interpolation-ticks` / `render.teleport-ticks` are replaced by the `network.` keys.
- A vehicle with a looping idle animation holds its last frame while asleep (`physics.sleep.keep-animations: true`
  keeps it awake instead).

### Fixed
- `/sbv debug physics` subscriptions were kept after the player left.

## 0.7.0 - 2026-11-15

### Fixed
- **The shader HUD broke every server pack on 26.3 clients.** Its text shader used `gl_VertexID`, which Minecraft 26.3
  (SPIR-V) rejects. One failing core shader makes the client drop ALL server packs, so CCTV's camera looks / HUD and
  Lighting's coloured light disappeared with it (SuperbVehicles then re-sent only its own models). Now
  `gl_VertexIndex`; checked with glslang and on a real 26.3 client together with CCTV and Lighting.
- **Vehicles stuck at slabs and blocks.** The step height was multiplied by the model scale (0.6 at scale 0.6 was
  0.36 blocks, lower than a slab), and servers still had 0.1.0 vehicle files with 0.6-0.7. Step heights are now in
  world blocks and never below `physics.min-step-height` (1.05): every car and tank drives up slabs and full blocks by
  just driving at them, from any angle and speed (unit sweep of every ground type, plus real Paper blocks).
- The same-port pack host answered 404 to downloads meant for other plugins on the same port; it now passes them on
  (CCTV 0.7 and Lighting 0.2 serve their packs on the Minecraft port the same way).

### Added
- Chase view zoom: the mouse wheel moves the camera closer / further (`camera.zoom-step`, `min-zoom`, `max-zoom`);
  `/sbv camera zoom <percent>` too. While the chase view is on, the held hotbar slot is pinned to `camera.scroll-slot`
  (7) so the wheel can be read; number keys 1-5 still pick weapons.
- With the CCTV plugin installed, the HUD text shader carries CCTV's hooks and include files (`hud.merge-cctv`), so
  both plugins' shaders work whichever pack ends up on top.
- `/sbv debug drive <seconds> [throttle]`: every empty vehicle drives straight ahead (console testing).

## 0.6.0 - 2026-11-15

### Added
- **Third-person chase view for helicopter pilots.** The pilot's seat moves behind and above the helicopter along
  their view direction (like F5 with the aircraft centred), pulled in front of walls. Others see a mannequin with the
  pilot's skin, name and equipment in the cockpit; the pilot's own body is hidden (short self-refreshing
  invisibility, equipment sent empty to other players; the tab list is untouched). Riders in the chase view take no
  suffocation, fire or contact damage at the camera spot. `/sbv camera [chase|cockpit]` per player (saved),
  `camera:` in config.yml (default view, distance, height, classes, seats, stand-in).
- **Simple helicopter flight model (new default).** W/S fly forward / back, A/D strafe, the helicopter turns to face
  where you look, Space climbs, Shift descends; no keys brakes to a hover and holds altitude. Speed builds up over
  `accel-time` and bleeds off over `brake-time`; the body lean is only visual. Ctrl fires, right-click pops flares,
  F toggles the engine (`controls.helicopter-simple`). The 0.2 model stays available with
  `physics.flight-model: realistic`.
- Pilots see a short controls line when they take the pilot seat.

### Fixed
- Chat messages showed vehicle names with raw MiniMessage tags ("<gold>AH-6 ...").

### Changed
- Simple-model pilots: a Shift held from the air no longer dismounts at touchdown (press it again to get out);
  bailing out is Space + Shift held for `seats.bail-hold-seconds` (1 s) instead of a double-tapped Shift.
- `/sbv seat <n>` also enters the vehicle you are looking at (before, only while already seated).
- Driver weapon keys that are one-shot presses (right-click, Q, F, left-click) now also reach the driver's weapons.

## 0.5.0 - 2026-11-14

### Fixed
- **Crashing did not damage vehicles.** The impact was measured as the distance missed in one sub-step of a tick, so a
  25 blocks/s wall hit read as 0-8 blocks/s and rarely passed the 6 blocks/s crash threshold. The impact is now the
  speed the collision actually takes away, for every class (cars, tanks, boats, helicopters, planes). Hard hits damage
  the vehicle (`physics.crash.vehicle-damage` per block/s) and its riders.

### Added
- Curb strikes: a step higher than `free-step` (0.55, so slabs and paths stay free) taken faster than `curb-speed`
  (8 blocks/s) damages the vehicle and slows it. Creeping onto a full block is still free.
- Ramming: mobs, players and other vehicles in the way are damaged and knocked back by the closing speed (heavier
  vehicles hit harder); the rammer takes part of the damage and loses speed. Vehicle-on-vehicle hits share momentum.
  `physics.ram.enabled`, `min-speed`, `self-damage`, `physics.crash.ram-damage`.

## 0.4.0 - 2026-11-14

### Fixed
- **Vehicles got stuck at slabs and small blocks.** The ride height came from the average of four corner probes; half
  on a slab, that average lowered the flat collision box into the slab and from inside a block the collider refused to
  move. Ground contact now comes from the collider itself: the box rests on the highest block under it, is snapped down
  steps so it stays on the ground, and the corners only tilt the visible body. The collision skin no longer sits on the
  bottom face (vehicles rested 2 cm into the floor).
- Steps are eased visually (body and riders) instead of snapping up a slab in one tick.

### Changed
- Default `step-height` 1.05 for cars and trucks (slabs, paths, stairs and full blocks), 1.1 for tracked vehicles.
- Boats: new model. They run up slabs and shores (`step-height`), slow down hard on land (`land-friction`), get
  `land-grace` seconds (3) of engine push toward the water (`land-push`), then they are stranded. Driver messages when
  running aground and when stranded.
- Seats: `offset` is now in blocks [right, up, forward]. New `/sbv seatoffset <type> <seat> <r> <u> <f> | add | reset`
  sets it live for every vehicle of the type and saves it to the YAML.

### Tests
- 48 tests: slab strip at three angles, a slab road 4 blocks up and down, full blocks, carpet and path blocks, a
  two-block wall, parking half on a slab; boats in open water, beaching and stranding, reversing back into the water.

## 0.3.0 - 2026-11-14

### Fixed
- **Models were drawn scrambled**: the client turns an item display's item 180 degrees about Y before drawing it
  (verified in the 26.3 client); the renderer did not cancel it, so every part was drawn rotated half a turn about its
  own centre. Invisible on the symmetric placeholders, broken on real models. Each part's display now carries the
  inverse turn. A new end-to-end test checks every cube corner of an asymmetric model against Blockbench.
- Importer: faces with no texture are left out (were drawn with the first texture); tall textures are no longer turned
  into animations (a 256x512 atlas showed half its texture, flickering); textures that are not embedded are loaded from
  PNG files next to the .bbmodel; null objects count as locators.

### Changed
- The plugin no longer spins wheels, steers them or moves tracks: animate them in the model. Driving clips play at a
  speed-proportional rate (`animation-speed`). Un-animated wheel and track groups fold into the hull.
- Pack report: warning above 48 parts per vehicle, count of left-out textureless faces.

## 0.2.0 - 2026-11-13

### Fixed
- **Cars, trucks and tanks did not move.** The drive force was `engine-power / mass x 20` (a truck got 0.8 blocks/s^2)
  while rolling resistance was a fixed 1.2 blocks/s^2, so trucks, the LAV-150 and every tank could never leave zero
  and pickups barely crawled. New `WheeledVehicleController` with SI-style units (newtons, kilograms, blocks/s):
  engine force falling off toward max speed, service brake, handbrake, parking brake, rolling resistance and quadratic
  drag that never reverse the motion, lateral grip on the sideways component only, bicycle or differential steering,
  velocity rebuilt on the new heading.
- Resting contact with the floor counted as a collision: every horizontal move was blocked and vehicles only advanced
  through the step-up path (1-2 cm hover jitter). The collider now tests with a 2 cm skin, depenetrates hulls spawned
  slightly inside blocks, and ground vehicles collide with a yaw-aligned hull (the body lean is visual).
- **Helicopters were far too fast.** Vertical speed grew without limit (collective added to velocity every tick) and
  horizontal acceleration was ~30 blocks/s^2. New `HelicopterModel`: rotor-tilt thrust, quadratic drag that saturates
  at `max-speed`, auto-stabilise to hover, climb/descent targets, hard clamps for horizontal, climb, descent and yaw.
- Units audit: physics velocities are blocks/second everywhere (weapons, seats and HUD converted); one per-tick
  conversion point (`Units`).

### Added
- Shader HUD: speedometer, fuel bar/arc, warning and engine-off icons, aircraft VSI, artificial horizon and altimeter,
  rendered by modified 26.3 core text shaders from colour-encoded glyphs; three skins; separate resource pack; automatic
  text fallback; `/sbv hud shader|text|off`, `/sbv hud units kmh|mph`.
- `/sbv debug physics` per-tick toggle with a "why not moving" line; startup self-check; chunk-loading speed limit;
  NaN guard and 3x-overspeed freeze; parked vehicles sleep (about 0.001-0.004 ms per tick).
- Vehicle YAML keys: `engine-force`, `max-reverse-speed`, `rolling-resistance`, `drag-coefficient`, `brake-force`,
  `handbrake-force`, `parking-brake-force`, `lateral-grip`, `drift-grip`, `steer-falloff`, `max-yaw-rate`,
  `auto-start-engine`, `fuel.initial-fuel`, helicopter `climb-rate`/`descent-rate`/`max-tilt`/`hover-settle-time`/
  clamps, plane `engine-force`/clamps, `hud-skin`, `gauge`.
- Defaults retuned: cars 25-30 blocks/s, APCs and tanks 12-18, AH-6 28, Mi-28 32, A-10 55 (stall 18), boat 22.

### Compatibility
- Old 0.1.0 YAMLs still load: missing `engine-force` is derived from mass and max speed; per-tick config values
  (`physics.gravity: 0.08`, `max-speed-cap: 4`) are converted.

## 0.1.0 - 2026-11-12
- First release.
