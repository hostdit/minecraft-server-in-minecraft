# Minecraft server inside Minecraft

A Fabric mod for 1.21.1 that makes a Minecraft world host more Minecraft servers, on their own ports, inside the same process. Works in singleplayer and on a dedicated server. Guests join with a plain, unmodified 1.21.1 client.

It does three things. Your singleplayer world takes connections like a real server. A second server shows your world as a live 1:4 scale model on a table, with every player a small figure you can pick up. And any sign that says `[server]` becomes a whole extra server for as long as it's powered.

## Why

Excel, Outlook, OBS, Obsidian, Blender, PowerPoint, VLC, Word, The Sims 4, Terraria, FL Studio, VS Code, DaVinci Resolve, Unity, Firefox, Garry's Mod, the whole planet, an iPhone, Valheim, and now Minecraft.

Minecraft is the one host that already is a server, so running one inside it proves nothing. This one runs as many as you have levers.

## What's different about this one

Every previous build spoke Minecraft 1.8.9, because 1.8's chunk format is the last one simple enough to write from scratch. This one speaks 1.21.1, and it barely speaks it at all. The mod handles the handshake, status, login and configuration phases with the game's own packet codecs, then hands the socket over. From the play phase on, vanilla code runs the player.

So for the first time in the series the world does everything a world does. Survival, mobs, chests, redstone and the Nether all work.

## What it does

### The world door

Singleplayer only. Open a world and the game itself starts listening on port 25565. Friends join with a stock client and are normal players in your world. Your game keeps running while you sit in the pause menu.

An overlay at the top left shows the ports, the pid, the LAN address to hand out and who's online. A toast and a ping fire when someone joins or leaves. The server list entry shows your name, the world name, the in-game day, your biome, the weather, your health and the world icon.

### The Table

Port 25566 is a server whose world is a live 1:4 scale model of your overworld. Every player online is a small figure on the table with their own face, moving as they move. Build a tower on the real server and it grows on the table. Dig a hole and the hole appears.

Standing over the table, ops can:

- Right click a figure to pick it up and right click the table to put it down. That player is teleported to the spot
- Hit a figure with a stick. That player is kicked
- Place a block on the table and the matching 4x4x4 region of the real world fills with it. Break a table block and the region is cleared
- Sneak and jump on a cell to drop in to the real world there. `/table` brings you back, `/table back` returns you to where you were before

The table is a normal world, so redstone works on it and the figures are real entities:

- Tripwire across your base's cells fires when anyone walks in to it on the real server
- Pressure plates fire when a figure stands on them
- Power a table cell with a lever or any redstone and the real column under it is locked. Nobody but ops can break or place there

Chat from the table arrives on the real server with a `[Table]` prefix.

The table can also show any server started by a Server Sign. Right click a powered sign while you're on the table, or run `/table show <name>`, and the model is rebuilt from that server's world with its players as the figures. Every hands on feature then works on that server: pick up and put down its players, kick them, edit its terrain, lock its land. Sneak and jump on a cell and you're transferred in to that server at that spot.

`/table show world` brings your own world back. `/table list` names every server that's up. Put the signs on the table's rim and you've built a switchboard.

### Server Signs

Put a sign on any block, write `[server]` on the first line and a name on the lines below. That block is now a server rack. Guests have nothing new to install, because a sign is a sign.

- Power the sign or the block it's on with a lever, a redstone line, a daylight sensor, anything. While it's powered a whole Minecraft server runs for it, on its own port, with its own world under `servers/`
- The signal strength is the player cap, so a plain lever gives 15
- The sign text glows green while the server is up
- A comparator facing the sign or the block behind it reads the player count, so lamps, note blocks and doors can react to people joining
- Right click the sign as a guest and you're sent to that server. Sneak to edit the sign
- Cut the power and everyone on it gets "Server powered down". Break the sign and they get "Server unplugged". Power it again and the same world comes back

Each sign keeps its port, from 25567 upwards, in `mcinmc-ports.json` in the world folder. Signs work in any dimension, the table included.

## What it doesn't do

**No authentication.** Guests aren't checked with Mojang on any door. Anyone who can reach the port can join under any name, so hand the address to people you trust.

**Only ops touch the real world from the table.** Everyone else can walk on it and watch. Their edits are refused.

**One level deep.** Signs inside a Server Sign's server do nothing for now.

**The singleplayer host can't ride a sign.** A client can't leave its own world, so right clicking a sign as the host gives you the address to connect to by hand.

**Nothing listens at the main menu.** Every door opens when a world finishes loading and closes the moment it starts shutting down.

**Internet guests need a forwarded port,** same as any other server.

## Requirements

Minecraft Java 1.21.1 with Fabric Loader for 1.21.1 and Fabric API. Guests need a stock 1.21.1 client and nothing else.

The jar is the same on Windows, Mac and Linux.

Building needs a Java 21 JDK.

## Install

**1.** Install Fabric Loader for 1.21.1 and drop Fabric API in `mods`.

**2.** Drop `mcinmc-1.0.0.jar` in `mods`.

**3.** Open a world or start the server.

### Config

Ports and the model are set with JVM flags.

| Flag | Default | Sets |
| --- | --- | --- |
| `-Dmcinmc.port` | `25565` | the world door |
| `-Dmcinmc.tablePort` | `25566` | the table |
| `-Dmcinmc.fleetPort` | `25567` | the first Server Sign port, counting up from there |
| `-Dmcinmc.tableRadius` | `256` | how far out from the centre the model reaches |
| `-Dmcinmc.tableShift` | `2` | the model scale as a power of two, so 2 is 1:4 |

## Proving it's one process

```
lsof -nP -iTCP:25565 -sTCP:LISTEN -iTCP:25566 -iTCP:25567
```

Every port belongs to the one Minecraft process. The pid in that output is the pid in the server list MOTD.

## Build

```
./gradlew build
```

The jar lands in `build/libs`.

## How it works

**The handover.** The mod accepts the TCP connection and speaks the handshake, status, login and configuration phases itself, with the game's own packet codecs. Once the guest reaches the play phase, the mod hands the socket to a connection object the server treats as its own. Vanilla server code runs the player from then on, which is why nothing about gameplay had to be written.

**The table dimension.** Guests through the table door are placed in `mcinmc:table`, a void world the mod fills with the model. The model is rebuilt cell by cell from block change events on the shown world and op edits on the table are written back the other way.

**Showing another server.** When the table shows a Server Sign's server, reads and writes of that world run on that server's own thread. Only the finished cells cross to the table.

**A server per sign.** A powered Server Sign boots a second `MinecraftServer` inside the process, built the way Mojang's own in process test server is built, with its own thread, save folder and Netty listener. Once a second the mod checks every loaded sign that starts with `[server]` and starts or stops its server to match the power state. Each one keeps its own tick rate.

## Licence

MIT. Do what you like with it.

Minecraft belongs to Mojang. Nothing from the game ships here.
