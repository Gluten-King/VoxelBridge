# Human-review showcase. Each colored lane targets a different renderer family.
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule randomTickSpeed 0
time set noon
weather clear
gamemode creative @s
kill @e[tag=voxelbridge_golden]
fill 0 60 0 47 75 31 air

# Stable floor and five visually separated review lanes.
fill 0 63 0 47 63 31 minecraft:smooth_stone
fill 1 63 1 46 63 5 minecraft:light_blue_concrete
fill 1 63 7 46 63 13 minecraft:red_concrete
fill 1 63 15 46 63 20 minecraft:cyan_concrete
fill 1 63 22 46 63 26 minecraft:yellow_concrete
fill 1 63 28 46 63 30 minecraft:purple_concrete

# Lane 1: asymmetric textures, rotations, axes and model-state changes.
setblock 2 64 2 minecraft:observer[facing=north,powered=false]
setblock 5 64 2 minecraft:observer[facing=east,powered=false]
setblock 8 64 2 minecraft:observer[facing=south,powered=false]
setblock 11 64 2 minecraft:observer[facing=west,powered=false]
setblock 14 64 2 minecraft:furnace[facing=north,lit=false]
setblock 17 64 2 minecraft:furnace[facing=east,lit=true]
setblock 20 64 2 minecraft:carved_pumpkin[facing=south]
setblock 23 64 2 minecraft:crafting_table
setblock 26 64 2 minecraft:oak_log[axis=x]
setblock 29 64 2 minecraft:oak_log[axis=y]
setblock 32 64 2 minecraft:oak_log[axis=z]
setblock 35 64 2 minecraft:white_glazed_terracotta[facing=north]
setblock 38 64 2 minecraft:white_glazed_terracotta[facing=east]
setblock 41 64 2 minecraft:white_glazed_terracotta[facing=south]
setblock 44 64 2 minecraft:white_glazed_terracotta[facing=west]

# Lane 2: culling oracle across X/Z/Y, translucent and partial shapes.
setblock 2 64 8 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 3 64 8 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 6 64 8 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 6 64 9 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 9 64 8 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 9 65 8 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 13 64 8 minecraft:spruce_leaves[distance=1,persistent=true,waterlogged=false]
setblock 14 64 8 minecraft:stone
setblock 17 64 8 minecraft:glass
setblock 18 64 8 minecraft:stone
setblock 21 64 8 minecraft:glass
setblock 21 64 9 minecraft:stone
setblock 24 64 8 minecraft:glass
setblock 24 65 8 minecraft:stone
setblock 28 64 8 minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]
setblock 29 64 8 minecraft:stone
setblock 32 64 8 minecraft:birch_slab[type=bottom,waterlogged=false]
setblock 33 64 8 minecraft:stone
setblock 36 64 8 minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=false]
setblock 37 64 8 minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=true]
setblock 40 64 8 minecraft:glass_pane[east=true,north=false,south=false,waterlogged=false,west=false]
setblock 41 64 8 minecraft:glass_pane[east=false,north=false,south=false,waterlogged=false,west=true]
fill 43 64 8 45 66 10 minecraft:stone
setblock 44 65 9 minecraft:air

# Lane 3: tint, source fluids, translucency and emissive-looking controls.
setblock 2 64 16 minecraft:grass_block[snowy=false]
setblock 4 64 16 minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]
setblock 6 64 16 minecraft:azalea_leaves[distance=1,persistent=true,waterlogged=false]
fill 2 63 18 8 63 20 minecraft:stone
fill 2 64 18 8 65 20 minecraft:glass
fill 3 64 19 7 65 19 minecraft:water[level=0]
fill 11 63 18 17 63 20 minecraft:stone
fill 11 64 18 17 65 20 minecraft:glass
fill 12 64 19 16 65 19 minecraft:lava[level=0]
setblock 20 64 16 minecraft:ice
setblock 23 64 16 minecraft:honey_block
setblock 26 64 16 minecraft:slime_block
setblock 29 64 16 minecraft:red_stained_glass
setblock 32 64 16 minecraft:green_stained_glass
setblock 35 64 16 minecraft:blue_stained_glass
setblock 38 64 16 minecraft:sea_lantern
setblock 41 64 16 minecraft:glowstone
setblock 44 64 16 minecraft:campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]

# Lane 4: block entities and the patterned-banner atlas regression family.
setblock 2 64 23 minecraft:chest[facing=north,type=single,waterlogged=false]
setblock 5 64 23 minecraft:trapped_chest[facing=east,type=single,waterlogged=false]
setblock 8 64 23 minecraft:ender_chest[facing=south,waterlogged=false]
setblock 11 64 23 minecraft:white_shulker_box
setblock 14 64 23 minecraft:blue_shulker_box
setblock 17 64 23 minecraft:white_banner[rotation=1]{patterns:[{pattern:"minecraft:rhombus",color:"cyan"},{pattern:"minecraft:stripe_bottom",color:"light_gray"},{pattern:"minecraft:stripe_center",color:"gray"},{pattern:"minecraft:border",color:"black"}]}
setblock 20 64 23 minecraft:white_banner[rotation=6]{patterns:[{pattern:"minecraft:gradient",color:"red"},{pattern:"minecraft:circle",color:"yellow"},{pattern:"minecraft:border",color:"black"}]}
setblock 23 64 23 minecraft:white_banner[rotation=11]{patterns:[{pattern:"minecraft:half_horizontal",color:"blue"},{pattern:"minecraft:stripe_middle",color:"white"},{pattern:"minecraft:bricks",color:"light_gray"},{pattern:"minecraft:border",color:"black"}]}
setblock 26 64 23 minecraft:red_bed[part=foot,facing=south,occupied=false]
setblock 26 64 24 minecraft:red_bed[part=head,facing=south,occupied=false]
setblock 30 64 23 minecraft:bell[attachment=floor,facing=north,powered=false]
setblock 33 64 23 minecraft:enchanting_table
setblock 36 64 23 minecraft:brewing_stand
setblock 39 64 23 minecraft:beacon
setblock 42 64 23 minecraft:conduit[waterlogged=false]
setblock 45 64 23 minecraft:decorated_pot

# Lane 5: stable entity renderer samples on a high-contrast floor.
summon minecraft:armor_stand 4 64 29 {Tags:["voxelbridge_golden"],NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,ShowArms:1b,NoBasePlate:1b,Rotation:[180.0f,0.0f]}
summon minecraft:armor_stand 10 64 29 {Tags:["voxelbridge_golden"],NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Small:1b,ShowArms:1b,Rotation:[135.0f,0.0f]}
summon minecraft:minecart 16 64 29 {Tags:["voxelbridge_golden"],Silent:1b,Invulnerable:1b}
summon minecraft:oak_boat 22 64 29 {Tags:["voxelbridge_golden"],Invulnerable:1b,Rotation:[90.0f,0.0f]}
summon minecraft:cow 30 64 29 {Tags:["voxelbridge_golden"],NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Rotation:[180.0f,0.0f]}
summon minecraft:pig 37 64 29 {Tags:["voxelbridge_golden"],NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Rotation:[180.0f,0.0f]}
summon minecraft:creeper 44 64 29 {Tags:["voxelbridge_golden"],NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Rotation:[180.0f,0.0f]}

# Review camera: centered south of the arena and pitched toward all five lanes.
tp @s 24 73 42 180 24
