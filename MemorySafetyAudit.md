# Memory safety audit (0.8.0)

What keeps the heap flat over weeks of uptime, and how each point was checked.

## Per-tick allocation

| Area | State | Checked by |
|---|---|---|
| `VehicleRigidBodyPhysics.step` + `VoxelCollisionAndStepSolver` | No allocation. All vectors, quaternions and matrices are fields created once per vehicle; block boxes go into a reused `BoxBuffer`. | `RigidBodyPhysicsTest.stepAllocatesNothing`: 2000 ticks over slabs and blocks, measured with `ThreadMXBean.getThreadAllocatedBytes`, must stay under 2 KB. |
| Reading blocks (`WorldVoxels.collect`) | Our code allocates nothing; shapes are cached per block state. The Bukkit calls underneath (`getBlockData`) create short-lived objects for each solid block read. | Not measurable in a unit test. Young-generation garbage only; nothing is retained. |
| Sleeping vehicles | Not ticked: no physics, no rendering, no teleports, no allocation. | Boot test: `/sbv debug perf` shows awake / asleep counts. |
| Rendering and animation of awake vehicles | Still allocates per tick (rig matrices, Bukkit `Transformation` objects). Unchanged from 0.7; short-lived. | Known, not addressed in this release. |
| Packet bundling | One small list per player per tick that had vehicle packets. | `BundleHandlerTest`. |

## References to players, entities, worlds

- Players are never stored. Every per-player map is keyed by `UUID`.
- Removed on `PlayerQuitEvent`: input cache and key state (`SeatManager`), pending exits
  (`PassengerAndSafeDismountController`), the packet bundler of the connection
  (`VehicleNetworkAndSleepManager`), HUD viewer / boss bar / shader state (`HudManager`), the physics debug
  subscription (`VehicleManager`, was missing before 0.8.0), the chase-camera stand-in.
- Kept after quit on purpose, a few bytes per player who used the command: `/sbv camera` and `/sbv hud` choices.
  They are bounded by the number of distinct players since the last restart.
- A vehicle holds its own entities (root, hull, seats, parts). They are cleared in `despawnEntities()`, which runs
  on removal, chunk unload, world unload and plugin disable. `EntitiesUnloadEvent` is a second net: a vehicle whose
  entities went away with their chunk is parked as a record one tick later.
- `byEntity` (entity UUID to vehicle) is emptied for a vehicle whenever it despawns or is removed.
- Per-world objects (`BlockCollider`, `WorldVoxels`) are dropped on `WorldUnloadEvent`.

## Bounded collections

| Collection | Bound |
|---|---|
| `WorldVoxels.shapes` (block state to boxes) | Cleared at 4096 entries. |
| `WorldVoxels.surfaces` | `EnumMap` over `Material`. |
| `BoxBuffer` | Grows to the largest region one vehicle has ever read, then stays. |
| `BundleHandler.packets` | Flushed every tick end, and early at 2048 packets. |
| Vehicle entity-id array (bundler) | Rebuilt from live vehicles when membership changes. |
| Sleep roster | Rebuilt from the vehicle map when membership changes. |
| `ramCooldown` (per vehicle) | Entries older than 40 ticks are dropped on every ram check. |
| `leaving` (pending exits) | An entry lives one tick; also cleared on quit and disable. |

## Tasks and listeners

- Two repeating tasks (tick loop, orphan sweep), both from `VehicleManager.start()`; the tick loop is cancelled in
  `shutdown()`, and Paper cancels all of a plugin's tasks on disable.
- Netty handlers: one per online player, removed on quit and on disable; a handler that is removed first sends
  whatever it still holds.
- Event listeners are registered once in `onEnable` and unregistered by Paper on disable.

## Not verified

- Heap behaviour over real uptime with real players. Everything above is from reading the code, unit tests and a
  headless boot test.
