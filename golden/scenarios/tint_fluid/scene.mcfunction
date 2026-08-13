gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule randomTickSpeed 0
time set noon
weather clear
tp @s 8 67 20
kill @e[tag=voxelbridge_golden]
fill 0 60 0 15 72 15 air
setblock 1 64 1 minecraft:grass_block[snowy=false]
setblock 3 64 1 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
fill 1 63 5 5 63 9 minecraft:stone
fill 1 64 5 5 64 9 minecraft:stone outline
fill 2 64 6 4 64 8 minecraft:water[level=0]
fill 8 63 5 12 63 9 minecraft:stone
fill 8 64 5 12 64 9 minecraft:stone outline
fill 9 64 6 11 64 8 minecraft:lava[level=0]
