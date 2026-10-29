# Small Create scene covering ordinary baked models and Flywheel-backed block entities.
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule randomTickSpeed 0
time set noon
weather clear
tp @s 8 68 20
fill 0 60 0 15 72 15 air
fill 0 63 0 15 63 15 minecraft:stone

# Basic kinetic components use Flywheel in a normal client frame.
setblock 2 64 2 create:shaft
setblock 4 64 2 create:cogwheel
setblock 6 64 2 create:large_cogwheel
setblock 8 64 2 create:gearbox
setblock 10 64 2 create:creative_motor

# Compound renderers exercise partial models in addition to the base block model.
setblock 2 64 6 create:mechanical_press
setblock 5 64 6 create:mechanical_mixer
setblock 8 64 6 create:encased_fan
setblock 11 64 6 create:mechanical_saw
setblock 2 64 10 create:millstone
setblock 5 64 10 create:deployer
setblock 8 64 10 create:rope_pulley
setblock 11 64 10 create:fluid_tank
