# Full 1.21.11 replacement: entity item stacks use the lowercase count field.
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule randomTickSpeed 0
time set noon
weather clear
tp @s 10 67 18
kill @e[tag=voxelbridge_golden]
fill 0 60 0 20 72 12 air
fill 0 63 0 20 63 12 minecraft:stone
summon minecraft:armor_stand 3 64 3 {Tags:["voxelbridge_golden"],NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,ShowArms:1b,NoBasePlate:1b,Rotation:[180.0f,0.0f]}
summon minecraft:minecart 8 64 3 {Tags:["voxelbridge_golden"],Silent:1b,Invulnerable:1b}
summon minecraft:item 13 64 3 {Tags:["voxelbridge_golden"],Item:{id:"minecraft:diamond",count:1},PickupDelay:32767s,Age:-32768s}
