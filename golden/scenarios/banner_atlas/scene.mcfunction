# Minimal atlas-mode regression scene for the banner block-entity renderer.
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule randomTickSpeed 0
time set noon
weather clear
tp @s 2 66 8
kill @e[tag=voxelbridge_golden]
fill 0 60 0 7 68 3 air

setblock 1 64 1 minecraft:white_banner[rotation=11]{patterns:[{pattern:"minecraft:rhombus",color:"cyan"},{pattern:"minecraft:stripe_bottom",color:"light_gray"},{pattern:"minecraft:stripe_center",color:"gray"},{pattern:"minecraft:border",color:"light_gray"},{pattern:"minecraft:stripe_middle",color:"black"},{pattern:"minecraft:half_horizontal",color:"light_gray"},{pattern:"minecraft:circle",color:"light_gray"},{pattern:"minecraft:border",color:"black"}]}
setblock 3 64 1 minecraft:white_banner[rotation=1]{patterns:[{pattern:"minecraft:rhombus",color:"cyan"},{pattern:"minecraft:stripe_bottom",color:"light_gray"},{pattern:"minecraft:stripe_center",color:"gray"},{pattern:"minecraft:border",color:"light_gray"},{pattern:"minecraft:stripe_middle",color:"black"},{pattern:"minecraft:half_horizontal",color:"light_gray"},{pattern:"minecraft:circle",color:"light_gray"},{pattern:"minecraft:border",color:"black"}]}
setblock 5 64 1 minecraft:white_banner[rotation=12]{patterns:[{pattern:"minecraft:rhombus",color:"cyan"},{pattern:"minecraft:stripe_bottom",color:"light_gray"},{pattern:"minecraft:stripe_center",color:"gray"},{pattern:"minecraft:border",color:"light_gray"},{pattern:"minecraft:stripe_middle",color:"black"},{pattern:"minecraft:half_horizontal",color:"light_gray"},{pattern:"minecraft:circle",color:"light_gray"},{pattern:"minecraft:border",color:"black"}]}
tick freeze
