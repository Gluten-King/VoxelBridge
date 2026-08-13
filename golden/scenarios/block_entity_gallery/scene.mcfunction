gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule randomTickSpeed 0
time set noon
weather clear
tp @s 10 67 18
kill @e[tag=voxelbridge_golden]
fill 0 60 0 20 72 12 air
setblock 1 64 1 minecraft:chest[facing=north,type=single,waterlogged=false]
setblock 4 64 1 minecraft:ender_chest[facing=north,waterlogged=false]
setblock 7 64 1 minecraft:white_shulker_box
setblock 10 64 1 minecraft:white_banner[rotation=0]
setblock 13 64 1 minecraft:bell[attachment=floor,facing=north,powered=false]
setblock 16 64 1 minecraft:red_bed[part=foot,facing=south,occupied=false]
setblock 16 64 2 minecraft:red_bed[part=head,facing=south,occupied=false]
