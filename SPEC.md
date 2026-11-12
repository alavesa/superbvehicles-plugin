# SuperbVehicles - spec (pasted by Piia 2026-11-11, from the team)

ROLE: senior Java engineer / Minecraft server-plugin architect. Build a complete Paper plugin "SuperbVehicles" that recreates the VEHICLE SYSTEM of the "Superb Warfare" mod, plus a data-driven framework for adding vehicles from Blockbench (.bbmodel) files.

TARGET: Paper 26.2 (spec says; the live server actually runs 26.3). Paper API only; NMS isolated in a `compat` package. Java version Paper requires. Gradle Kotlin DSL (gradle is NOT installed on this Mac; Maven offline works). paper-plugin.yml. Verify the exact Paper APIs first (Display/Interaction entities, PlayerInputEvent, item_model component, resource pack sending, Brigadier, Scheduler). Vanilla clients only, server-generated resource pack.

IN SCOPE: drivable/flyable/sailable vehicles, static emplacements, vehicle-mounted weapons, damage/armor, fuel, ammo, HUD, sounds, BBModel pipeline, pack generation, commands, API. OUT: handheld guns, player armor, perks.
CLEAN-ROOM: no Superb Warfare code/models/textures/sounds (GPLv3 / CC BY-NC-SA); original procedurally generated placeholder bbmodels.

MODULES: core, bbmodel, pack, render, physics, seat, weapon, damage, hud, command, api. All vehicle behaviour data-driven: a vehicle = YAML + .bbmodel (+ textures/sounds), zero Java.

BBMODEL PIPELINE: parse meta/resolution/elements (cubes incl. per-face uv/rotation/inflate)/outliner (nested bones)/textures (base64)/animations (linear, catmullrom, step; numeric only)/locators (seats, muzzles, exhaust, wheel centres, rotor hubs). Validate with actionable errors; never crash on a bad model. Bone naming convention drives behaviour (root, body, turret, gun/barrel, wheel_*, track_l/track_r, rotor_main, rotor_tail, propeller, gear_*, flap_*, seat_*, muzzle_*, hitbox_*), overridable in YAML. Convert to vanilla item models respecting limits (22.5-degree single-axis element rotations; split otherwise and bake into display transforms; meshes -> warn/approximate). One item model per animatable bone group. item_model component (not CustomModelData). Hot reload /sbv reload. Unit tests with fixtures.

RENDERING: root entity + N ItemDisplay parts with Transformation from physics + animations (wheel spin, steering, turret yaw/barrel pitch, rotors, gear, flaps, damage states); interpolation; update only changed transforms; animation player (play/loop/blend) by state; damage-state swaps + particles.

INPUT/SEATS/COLLISION: PlayerInputEvent + look direction; configurable controls per class; hotbar switches weapon; seats from seat_* locators (driver/gunner/copilot/passenger), seat switching, dismount rules; Interaction entities for enter + hits; own OBB-vs-block collision (stepping, slopes, optional block breaking), vehicle-vs-entity/vehicle damage; ray-vs-OBB hit zones.

PHYSICS (YAML tunable, fixed 20 TPS, substeps, clamped): wheeled (engine curve, gears/RPM, speed-dependent steering, brakes, handbrake, drifting, per-wheel suspension raycast, body roll/pitch), tracked (differential steering, neutral turn, momentum, track anim), boat (buoyancy, planing, rudder, wake, no land), helicopter (collective, cyclic, pedals, spin-up, torque, auto-stabilise, ground effect, autorotation), plane (throttle, lift, stall, mouse pitch/roll/yaw, takeoff roll, gear, flaps, airbrake, ejection), static emplacements.

DAMAGE/RESOURCES: health, per-zone armour, damage types, penetration, engine damage, fire, wreck, explosion; fuel (refuel via item/command); ammo per weapon with reload + supply items; repair tool; ownership/locking, team/friendly-fire hooks, protection-plugin soft hooks.

WEAPONS (mounted): autocannon, MG, main cannon (AP/HE), rockets, guided missiles (TOW-like, laser, lock-on), bombs, mortar/artillery, flares/chaff/smoke, CIWS auto-turret. Each: cooldown, spread, velocity, gravity, damage, splash, block-damage toggle, ammo, muzzle locators, sounds, particles, lock rules, recoil. Plugin-simulated projectiles, capped.

HUD: speed/altitude/fuel/health/ammo/lock warning/gear/flaps via action bar/boss bar/title + optional font-glyph HUD. Sounds in the pack (sounds.json generated), engine loop pitch by RPM. Particles.

ROSTER (verify against the mod's changelog): Truck; "Sodayo" pickups; LAV-150; BMP-2; TOM-6 (verify); PLZ-05; Speedboat; AH-6; Mi-28; A-10; TOW launcher; HPJ-11; Laser Tower; Waveforce Tower; maybe Mk-42, mortar, HMG emplacement, AA emplacement, Annihilator, Wheelchair. Stats approximate; variants/skins via textures.

YAML SCHEMA example: id, display-name, class (wheeled|tracked|boat|helicopter|plane|static), model, textures{}, physics{mass,engine-power,max-speed,turn-rate,step-height}, health, armor{front,side,rear,top}, fuel{capacity,consumption}, seats[{name,locator,role,weapons}], weapons{name:{type,muzzle,damage,rpm,ammo}}, hitzones{}, animations{}, sounds{}, recipe{}.

COMMANDS: /sbv give|spawn|remove|removeall|list|info|repair|refuel|ammo|skin|lock|unlock|tp|reload|pack (rebuild|send|url)|import <file.bbmodel>|debug (hitboxes|physics|seats|perf). Brigadier + tab completion; permissions superbvehicles.use.<id>, .spawn.<id>, .admin. PDC-tagged spawn items; persistence across restarts; no dupes.

API/EVENTS: SuperbVehiclesAPI (register types, spawn/query/remove, by entity/player); events VehicleSpawn/Enter/Exit/Damage/Destroy/WeaponFire/MissileLock.

PERFORMANCE: 50 vehicles < 1 ms each; pooling; caps; orphan cleanup; async parsing/pack build; Folia-friendly where practical; config.yml + per-vehicle files + messages.yml (MiniMessage).

PACK DELIVERY: plugins/SuperbVehicles/pack/, zip, SHA-1; built-in HTTP host | external URL | merge into an existing pack folder; send on join with configurable prompt/required; re-send on change.

DELIVERABLES: architecture summary + verified API list; file tree; complete compilable source (no TODOs); build file, paper-plugin.yml, config.yml, messages.yml; YAML + placeholder bbmodel for every roster vehicle; unit tests (parser/converter/physics); README (install, authoring rules, add a vehicle in 5 minutes, controls per class, troubleshooting); known limitations.

WORKING RULES: phases (1) core+bbmodel+pack, (2) render+seats+ground, (3) tracked+boats, (4) heli+planes, (5) weapons+damage, (6) HUD/sound/commands/API, (7) roster/tests/README. No clarifying questions; state assumptions. Acceptance: fresh Paper server loads it, builds+sends the pack, /sbv give spawns each vehicle, renders+animates from its bbmodel, drivable with documented controls, damage + destruction, no console spam. Owner can add own bbmodels and edit the vehicle config.
