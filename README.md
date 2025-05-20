# Links
- [About](#altoclef)
- [Versions](#versions)
- [Download](#download)
- [FAQ](#faq)
- [Other useful files](#other-useful-files)

# AltoClef
*Plays block game.*

*Powered by Baritone.*

A client side bot that tries to beat Minecraft on its own...

**This fork is still under development and is nowhere near perfect, if you have any questions, suggestions, ideas or find a bug don't hesitate to reach out!
You can use the [issues](https://github.com/MiranCZ/altoclef/issues). Or contact me on discord!**

Became [the first bot to beat Minecraft fully autonomously](https://youtu.be/baAa6s8tahA) on May 24, 2021.

**Join the [Discord Server](https://discord.gg/JdFP4Kqdqc)** for discussions/updates/goofs & gaffs

## About this fork
This fork aims to improve AltoClef by fixing a lot of bugs and optimizing some things. The main focus is optimizing the `MarvionBeatMinecraftTask` (I will just refer to it as `BeatMinecraftTask`) from [Marvion's fork](https://github.com/MarvionKirito/altoclef).

Because I rewrote a good portion of the `BeatMinecraftTask` a lot of the config settings don't work. Although I plan to implement configs in the future of course.  

## The preprocessor
I am currently using the [replay mod preprocessor](https://github.com/ReplayMod/preprocessor) to keep the mod updated across multiple versions at the same time.

### Versions
Thanks to that, the mod is currently available on **fabric** for (basically) all versions between `1.21.1` and `1.16.5` (inclusive).

(If there is a specific version in this range that you would like to use, and it isn't supported you can try creating an issue).


> [!NOTE]   
> All of the versions use the "same release" of Altoclef, although some of them use older versions of baritone.
You can check out the baritone [here](https://github.com/MiranCZ/baritone_altoclef)


## How it works

Take a look at this [Guide from the wiki](https://github.com/MiranCZ/altoclef/wiki/1:-Documentation:-Big-Picture)
or this [Video explanation](https://youtu.be/q5OmcinQ2ck?t=387)


## Download
> [!IMPORTANT]  
> After installing, please move/delete your old baritone configurations if you have any. Preexisting baritone
configurations will interfere with altoclef and introduce bugs. This will be fixed in the future.


Here is a list of links for download of the latest versions for convenience. 

| Version | Fabric download link                                                                                          |
|---------|---------------------------------------------------------------------------------------------------------------|
| 1.21.1  | [AltoClef 0.19 download](https://github.com/MiranCZ/altoclef/releases/download/0.19/altoclef-1.21.1-0.19.jar) |
| 1.21    | [AltoClef 0.19 download](https://github.com/MiranCZ/altoclef/releases/download/0.19/altoclef-1.21-0.19.jar)   |
| 1.20.6  | [AltoClef 0.19 download](https://github.com/MiranCZ/altoclef/releases/download/0.19/altoclef-1.20.6-0.19.jar) |
| 1.20.5  | [AltoClef 0.19 download](https://github.com/MiranCZ/altoclef/releases/download/0.19/altoclef-1.20.5-0.19.jar) |
| 1.20.4  | [AltoClef 0.19 download](https://github.com/MiranCZ/altoclef/releases/download/0.19/altoclef-1.20.4-0.19.jar) |
| 1.20.2  | [AltoClef 0.19 download](https://github.com/MiranCZ/altoclef/releases/download/0.19/altoclef-1.20.2-0.19.jar) |
| 1.20.1  | [AltoClef 0.19 download](https://github.com/MiranCZ/altoclef/releases/download/0.19/altoclef-1.20.1-0.19.jar) |
| 1.19.4  | [AltoClef 0.19 download](https://github.com/MiranCZ/altoclef/releases/download/0.19/altoclef-1.19.4-0.19.jar) |
| 1.18.2  | [AltoClef 0.19 download](https://github.com/MiranCZ/altoclef/releases/download/0.19/altoclef-1.18.2-0.19.jar) |
| 1.18    | [AltoClef 0.19 download](https://github.com/MiranCZ/altoclef/releases/download/0.19/altoclef-1.18-0.19.jar)   |
| 1.17.1  | [AltoClef 0.19 download](https://github.com/MiranCZ/altoclef/releases/download/0.19/altoclef-1.17.1-0.19.jar) |
| 1.16.5  | [AltoClef 0.19 download](https://github.com/MiranCZ/altoclef/releases/download/0.19/altoclef-1.16.5-0.19.jar) |


You can also [check releases](https://github.com/MiranCZ/altoclef/releases) for all previous releases.


## FAQ

### My Altoclef is crashing! What do I do?

> [!WARNING]    
> If you are trying to run AltoClef on cracked launchers (TLauncher, launchers to run the game on mobile etc...) or unofficial launchers there is a high change I might not help you.

- First check if you downloaded the right file for the right Minecraft version, every release has the name in the following pattern: `altoclef-<minecraftVersion>-<altoclefVersion>.jar`.


- You **DO NOT** need to include baritone in your `mods` folder, it is already included in Altoclef. If you did include it, remove it.


- Altoclef **is not** intended to be used with other mods, so if the cause of the crash is another mod you are using it is very likely I won't do anything about that.


If you checked everything above and are still having trouble you can reach out to me on Altoclef discord (or create an issue).

**Your message should include the following things:** exactly what problem you are having,
what Minecraft version are you trying to run with what Altoclef version,
specify that you are referring to this fork (there are multiple forks, so it makes everyone's life easier), what mods (if any) you are using and a crash-log
(if the mod didn't crash on startup but rather after a specific action recording or description of that would also help).

### Why was `terminator` and the ability to attack players removed?
I don't feel like this bot should be focused on use on servers and having to handle players complicates things.
So I just decided to remove that behaviour (at least for now).

### Can you add X version of Minecraft?

**Please note that for newest version of minecraft I need to wait for [baritone](https://github.com/MeteorDevelopment/baritone) to be ported first. Unless there is a branch for the specific version, please do not message me about that version since I need to wait for baritone to be ported first.**


I am planning to support all versions from 1.16.5 and above.

You can open an issue if you want support for specific version in that range that isn't supported yet.

### Can you add integration with LLM?
no.


## Other useful files

### [Usage Guide](usage.md)

### [TODO's/Future Features](TODO.md)

### [Development Guide](develop.md)
