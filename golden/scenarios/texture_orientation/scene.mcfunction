gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule randomTickSpeed 0
time set noon
weather clear
tp @s 8 67 16
kill @e[tag=voxelbridge_golden]
fill 0 60 0 15 70 10 air
setblock 1 64 1 minecraft:observer[facing=north,powered=false]
setblock 3 64 1 minecraft:observer[facing=east,powered=false]
setblock 5 64 1 minecraft:furnace[facing=south,lit=false]
setblock 7 64 1 minecraft:carved_pumpkin[facing=west]
setblock 9 64 1 minecraft:crafting_table
setblock 11 64 1 minecraft:oak_log[axis=x]
setblock 13 64 1 minecraft:oak_log[axis=z]
setblock 2 64 5 minecraft:white_glazed_terracotta[facing=north]
setblock 5 64 5 minecraft:white_glazed_terracotta[facing=east]
setblock 8 64 5 minecraft:white_glazed_terracotta[facing=south]
setblock 11 64 5 minecraft:white_glazed_terracotta[facing=west]
