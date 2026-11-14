# Changelog

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
