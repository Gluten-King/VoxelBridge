# Reset the arena and remove entities from previous runs.
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule randomTickSpeed 0
time set noon
weather clear
# Keep the client close enough to load the arena, but outside the exported AABB.
tp @s 8 66 20
kill @e[tag=voxelbridge_golden]
fill 0 60 0 15 72 15 air

# Stable block-model coverage.
setblock 1 64 1 minecraft:stone
setblock 3 64 1 minecraft:grass_block[snowy=false]
setblock 5 64 1 minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]
setblock 7 64 1 minecraft:oak_slab[type=bottom,waterlogged=false]
setblock 9 64 1 minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]
setblock 11 64 1 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 13 64 1 minecraft:glass

# Contained source fluids.
fill 1 63 4 3 63 6 minecraft:stone
fill 1 64 4 3 64 6 minecraft:stone outline
setblock 2 64 5 minecraft:water[level=0]
fill 5 63 4 7 63 6 minecraft:stone
fill 5 64 4 7 64 6 minecraft:stone outline
setblock 6 64 5 minecraft:lava[level=0]

# Block entity and deterministic entity capture.
setblock 9 64 5 minecraft:chest[facing=north,type=single,waterlogged=false]
summon minecraft:armor_stand 12 64 5 {Tags:["voxelbridge_golden"],NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,ShowArms:1b,NoBasePlate:1b,Rotation:[180.0f,0.0f]}
