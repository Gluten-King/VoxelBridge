# Reset the arena and remove entities from previous runs.
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule randomTickSpeed 0
time set noon
weather clear
tp @s 8 66 20
kill @e[type=!minecraft:player,x=0,y=60,z=0,dx=15,dy=12,dz=15]
fill 0 60 0 15 72 15 air

# Entity and block-entity render capture.
setblock 1 64 1 minecraft:chest[facing=north,type=single,waterlogged=false]
summon minecraft:armor_stand 4 64 1 {Tags:["voxelbridge_golden"],NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,ShowArms:1b,NoBasePlate:1b,Rotation:[180.0f,0.0f]}

# Same nonsolid faces: retain one shared double-sided face, not two duplicates.
setblock 1 64 5 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 2 64 5 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 1 64 8 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 1 64 9 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 4 64 8 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 4 65 8 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]

# Nonsolid full cube against a solid full cube.
setblock 4 64 5 minecraft:spruce_leaves[distance=1,persistent=true,waterlogged=false]
setblock 5 64 5 minecraft:stone

# Partial block shapes whose boundary faces are fully covered by a solid neighbor.
setblock 7 64 5 minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]
setblock 8 64 5 minecraft:stone
setblock 10 64 5 minecraft:birch_slab[type=bottom,waterlogged=false]
setblock 11 64 5 minecraft:stone

# Translucent full cube against a solid full cube.
setblock 13 64 5 minecraft:glass
setblock 14 64 5 minecraft:stone

# Exercise nonsolid-vs-solid culling on SOUTH and UP, not only EAST.
setblock 7 64 9 minecraft:glass
setblock 7 64 10 minecraft:stone
setblock 10 64 9 minecraft:glass
setblock 10 65 9 minecraft:stone
