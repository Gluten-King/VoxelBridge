# Large, human-oriented Create 6 gallery. Rows are deliberately separated so
# each family can be selected and exported independently.
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule randomTickSpeed 0
time set noon
weather clear
tp @s 32 70 68
fill 0 60 0 63 75 31 air
fill 0 60 32 63 75 63 air
fill 0 63 0 63 63 63 minecraft:smooth_stone

# Row 1: kinetic transmission
setblock 2 64 4 minecraft:oak_sign[rotation=0]{front_text:{messages:['{"text":"KINETICS"}','{"text":"shafts / gears"}','{"text":"motors / gauges"}','{"text":"Z = 4"}']}}
setblock 6 64 4 create:shaft
setblock 10 64 4 create:cogwheel
setblock 14 64 4 create:large_cogwheel
setblock 18 64 4 create:gearbox
setblock 22 64 4 create:creative_motor
setblock 26 64 4 create:clutch
setblock 30 64 4 create:gearshift
setblock 34 64 4 create:sequenced_gearshift
setblock 38 64 4 create:adjustable_chain_gearshift
setblock 42 64 4 create:rotation_speed_controller
setblock 46 64 4 create:speedometer
setblock 50 64 4 create:stressometer
setblock 54 64 4 create:flywheel
setblock 58 64 4 create:encased_chain_drive

# Row 2: processing and actors
setblock 2 64 12 minecraft:oak_sign[rotation=0]{front_text:{messages:['{"text":"PROCESSING"}','{"text":"animated heads"}','{"text":"items / partials"}','{"text":"Z = 12"}']}}
setblock 6 64 12 create:mechanical_press
setblock 10 64 12 create:mechanical_mixer
setblock 14 64 12 create:millstone
setblock 18 64 12 create:crushing_wheel
setblock 22 64 12 create:mechanical_drill
setblock 26 64 12 create:mechanical_saw
setblock 30 64 12 create:deployer
setblock 34 64 12 create:mechanical_arm
setblock 38 64 12 create:encased_fan
setblock 42 64 12 create:basin
setblock 46 64 12 create:blaze_burner
setblock 50 64 12 create:lit_blaze_burner
setblock 54 64 12 create:item_drain
setblock 58 64 12 create:spout

# Row 3: transport. The manually-authored belt includes pulley, middle and end states.
setblock 2 64 20 minecraft:oak_sign[rotation=0]{front_text:{messages:['{"text":"LOGISTICS"}','{"text":"belts / funnels"}','{"text":"chutes / vault"}','{"text":"Z = 20"}']}}
setblock 6 64 20 create:belt[facing=east,part=pulley,slope=horizontal,casing=false,waterlogged=false]{IsController:1b,Length:6,Index:0}
setblock 7 64 20 create:belt[facing=east,part=middle,slope=horizontal,casing=false,waterlogged=false]{Controller:{X:6,Y:64,Z:20},Length:6,Index:1}
setblock 8 64 20 create:belt[facing=east,part=middle,slope=horizontal,casing=false,waterlogged=false]{Controller:{X:6,Y:64,Z:20},Length:6,Index:2}
setblock 9 64 20 create:belt[facing=east,part=middle,slope=horizontal,casing=true,waterlogged=false]{Controller:{X:6,Y:64,Z:20},Length:6,Index:3}
setblock 10 64 20 create:belt[facing=east,part=middle,slope=horizontal,casing=false,waterlogged=false]{Controller:{X:6,Y:64,Z:20},Length:6,Index:4}
setblock 11 64 20 create:belt[facing=east,part=pulley,slope=horizontal,casing=false,waterlogged=false]{Controller:{X:6,Y:64,Z:20},Length:6,Index:5}
summon minecraft:item 8.5 65.1 20.5 {Item:{id:"minecraft:diamond",count:1}}
summon minecraft:item 10.5 65.1 20.5 {Item:{id:"create:brass_casing",count:1}}
setblock 16 64 20 create:andesite_funnel
setblock 20 64 20 create:brass_funnel
setblock 24 64 20 create:andesite_tunnel
setblock 28 64 20 create:brass_tunnel
setblock 32 64 20 create:chute
setblock 36 64 20 create:smart_chute
fill 40 64 20 42 64 20 create:item_vault
setblock 46 64 20 create:portable_storage_interface
setblock 50 64 20 create:chain_conveyor
setblock 54 64 20 create:packager
setblock 58 64 20 create:package_frogport

# Row 4: fluids and steam. One tank is populated through its serialized fluid handler.
setblock 2 64 28 minecraft:oak_sign[rotation=0]{front_text:{messages:['{"text":"FLUIDS"}','{"text":"tanks / pipes"}','{"text":"steam / pulleys"}','{"text":"Z = 28"}']}}
setblock 6 64 28 create:fluid_tank[bottom=true,top=true,shape=window]{Size:1,Height:1,Window:1b,ForceFluidLevel:1b,TankContent:{FluidName:"minecraft:water",Amount:6000}}
setblock 10 64 28 create:creative_fluid_tank
setblock 14 64 28 create:fluid_pipe
setblock 18 64 28 create:glass_fluid_pipe
setblock 22 64 28 create:encased_fluid_pipe
setblock 26 64 28 create:smart_fluid_pipe
setblock 30 64 28 create:mechanical_pump
setblock 34 64 28 create:fluid_valve
setblock 38 64 28 create:hose_pulley
setblock 42 64 28 create:portable_fluid_interface
setblock 46 64 28 create:steam_engine
setblock 50 64 28 create:steam_whistle
setblock 54 64 28 create:nozzle
fill 58 64 28 59 65 29 create:fluid_tank

# Row 5: contraption anchors and moving-structure components
setblock 2 64 36 minecraft:oak_sign[rotation=0]{front_text:{messages:['{"text":"CONTRAPTIONS"}','{"text":"bearings / piston"}','{"text":"pulley / gantry"}','{"text":"Z = 36"}']}}
setblock 6 64 36 create:mechanical_bearing
setblock 10 64 36 create:windmill_bearing
setblock 14 64 36 create:clockwork_bearing
setblock 18 64 36 create:mechanical_piston
setblock 22 64 36 create:sticky_mechanical_piston
setblock 26 64 36 create:piston_extension_pole
setblock 30 64 36 create:rope_pulley
setblock 34 64 36 create:elevator_pulley
setblock 38 64 36 create:gantry_shaft
setblock 42 64 36 create:gantry_carriage
setblock 46 64 36 create:contraption_controls
setblock 50 64 36 create:red_seat
setblock 54 64 36 create:linear_chassis
setblock 58 64 36 create:radial_chassis

# Row 6: rail content and large/special baked models
setblock 2 64 44 minecraft:oak_sign[rotation=0]{front_text:{messages:['{"text":"TRAINS"}','{"text":"track / bogeys"}','{"text":"signals / doors"}','{"text":"Z = 44"}']}}
fill 6 64 44 14 64 44 create:track
setblock 18 64 44 create:track_station
setblock 22 64 44 create:track_signal
setblock 26 64 44 create:track_observer
setblock 30 64 44 create:controller_rail
setblock 34 64 44 create:small_bogey
setblock 38 64 44 create:large_bogey
setblock 42 64 44 create:train_door
setblock 46 64 44 create:train_trapdoor
setblock 50 64 44 create:water_wheel
setblock 54 64 44 create:large_water_wheel
setblock 58 64 44 create:turntable

# Row 7: displays, redstone and decorative Create models
setblock 2 64 52 minecraft:oak_sign[rotation=0]{front_text:{messages:['{"text":"DISPLAY"}','{"text":"boards / links"}','{"text":"doors / decor"}','{"text":"Z = 52"}']}}
fill 6 64 52 10 65 52 create:display_board
setblock 14 64 52 create:display_link
setblock 18 64 52 create:nixie_tube
setblock 22 64 52 create:stock_ticker
setblock 26 64 52 create:redstone_link
setblock 30 64 52 create:pulse_repeater
setblock 34 64 52 create:pulse_extender
setblock 38 64 52 create:powered_latch
setblock 42 64 52 create:analog_lever
setblock 46 64 52 create:placard
setblock 50 64 52 create:cuckoo_clock
setblock 54 64 52 create:train_trapdoor
setblock 58 64 52 create:schematicannon

# Row 8: representative Create add-ons
setblock 2 64 60 minecraft:oak_sign[rotation=0]{front_text:{messages:['{"text":"ADD-ONS"}','{"text":"Copycats + Deco"}','{"text":"static + Flywheel"}','{"text":"Z = 60"}']}}
setblock 6 64 60 copycats:copycat_block
setblock 10 64 60 copycats:copycat_slab
setblock 14 64 60 copycats:copycat_stairs
setblock 18 64 60 copycats:copycat_slope
setblock 22 64 60 copycats:copycat_beam
setblock 26 64 60 copycats:copycat_cogwheel
setblock 32 64 60 createdeco:andesite_catwalk
setblock 36 64 60 createdeco:copper_catwalk
setblock 40 64 60 createdeco:brass_window
setblock 44 64 60 createdeco:industrial_iron_support
setblock 48 64 60 createdeco:green_brass_lamp
setblock 52 64 60 createdeco:decal_cross

# Test tools for manual inspection and rebuilding functional machines.
give @s create:wrench 1
give @s create:goggles 1
give @s create:clipboard 1
give @s create:creative_motor 4
give @s create:shaft 32
give @s create:cogwheel 16
give @s create:large_cogwheel 16
give @s create:fluid_tank 16
give @s create:mechanical_pump 4
give @s create:track 64
give @s minecraft:water_bucket 4
gamemode creative @s
spawnpoint @s 32 65 68
