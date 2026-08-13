gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule randomTickSpeed 0
time set noon
weather clear
tp @s 8 67 20
kill @e[tag=voxelbridge_golden]
fill 0 60 0 15 72 15 air

# X, Z and Y pairs prove shared-face handling in all axes.
setblock 1 64 1 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 2 64 1 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 4 64 1 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 4 64 2 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 7 64 1 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 7 65 1 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]

# Nonsolid, translucent and partial shapes against opaque controls.
setblock 1 64 5 minecraft:spruce_leaves[distance=1,persistent=true,waterlogged=false]
setblock 2 64 5 minecraft:stone
setblock 4 64 5 minecraft:glass
setblock 5 64 5 minecraft:stone
setblock 7 64 5 minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]
setblock 8 64 5 minecraft:stone
setblock 10 64 5 minecraft:birch_slab[type=bottom,waterlogged=false]
setblock 11 64 5 minecraft:stone

# Exterior controls ensure an over-culling bug cannot pass by deleting a pair.
setblock 1 64 9 minecraft:glass
setblock 1 64 10 minecraft:stone
setblock 4 64 9 minecraft:glass
setblock 4 65 9 minecraft:stone
