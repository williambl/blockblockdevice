# Block Block Device
Block Block Device is a Minecraft mod and NBDKit plugin which work together to allow you to create block devices on
your computer which are backed by redstone in a Minecraft world.

See the video:

[![Video link](https://i1.ytimg.com/vi/oiCue9hUN-A/hqdefault.jpg)](https://youtu.be/oiCue9hUN-A)

## Usage

Requirements:
 - NBDKit
 - Minecraft 1.19.3
 - Some way of connecting to NBD servers (e.g. `nbd-client`)

Firstly, build and install the mod into your Minecraft game. This is the same procedure as any other [Fabric](https://fabricmc.org) mod.
The mod requires Minecraft version 1.19.3. If playing multiplayer, the mod must be installed serverside.

Start up the game, and enter a world.

Next, run NBDKit with the python plugin, like so:

`$ nbdkit -f -v python ./craftnbd/craftnbd.py`

You can then connect with your NBD client:

`# nbd-client -d /dev/nbd0`

On some systems, you may have to run `modprobe nbd` as root first.

You will now have the Block Block device at /dev/nbd0.

## Mod Contents

The mod exposes an HTTP server on port 8394, and allows reading and placing blocks in the Minecraft world through requests to this server.

The mod contains a command, `/generate_memory`, to fill a chunk with 'memory cells'.

The mod contains `/encode_chunk` and `/decode_chunk` commands to read and write memory from memory cells in chunks.
