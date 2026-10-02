# VoxelDevelopment

Development-session controls for the Voxel Horizons network.

The plugin is intentionally small and modular: a global development mode can be switched on/off at runtime, and feature modules can react to that state. The first module is a **join overlay/gate** used to show notices such as the Testing Network banner while temporarily blocking gameplay.

## Initial behaviour

When `/vdev on` is enabled and the join-overlay feature is enabled:

- players joining the server are placed into a development screen after a configurable delay;
- a hidden-particle **Blindness** effect darkens the world behind the screen;
- on modern Bukkit/Paper versions the blindness HUD icon is also hidden;
- a 54-slot blocker inventory is opened so normal movement/input is suppressed client-side;
- the inventory title is resolved through VoxelCore's `TextPlaceholderService`, so aliases such as `:offset_-8::development_testing_network:` can render custom bitmap UI artwork;
- PlaceholderAPI is also supported when installed;
- clicks, drags, movement, interactions, commands, chat, teleporting, damage and hunger changes can all be blocked while the screen is active;
- closing the blocker screen exits the notice and restores the player's previous Blindness state.

Minecraft sends the same close-container action for Escape and the inventory key, so a server plugin cannot reliably distinguish those two keys. In practice, any client-side close of this blocker GUI is treated as the exit action.

## Commands

```text
/vdev on
/vdev off
/vdev status
/vdev show <player>
/vdev clear <player|*>
/vdev reload
```

Aliases: `/voxeldev`, `/development`.

Permissions:

```text
voxeldevelopment.admin
voxeldevelopment.bypass
```

Players with the bypass permission are not automatically gated when they join. `/vdev show` still allows an administrator to preview the screen on a bypassed player.

## VoxelCore banner integration

VoxelDevelopment calls VoxelCore directly when it is present:

```java
VoxelCore.getInstance().getTextPlaceholderService().resolve(text)
```

That means the screen title can use the normal VoxelCore aliases and offsets instead of hard-coding allocated Unicode characters.

A matching VoxelCore UI definition for the example artwork can look like:

```yaml
ui:
  development_testing_network:
    path: ui/development/testing_network.png
    scale_ratio: 256
    y_position: 128
    gui: true
```

Tune `y_position` for the final resource-pack layout. Then set:

```yaml
features:
  join-overlay:
    inventory-title: ':offset_-8::development_testing_network:'
```

The example image from the initial design is kept under `docs/testing-network.png` for reference; it still needs to be placed in the VoxelCore content/resource-pack source tree to be compiled into the live pack.

## Build

```bash
mvn clean package
```

The GitHub Actions workflow builds API-specific descriptors from Minecraft 1.12 through 26.2, uploads the JARs as workflow artifacts, and publishes a rolling `latest` prerelease after pushes to `main`.

The implementation deliberately compiles against the 1.12.2 Spigot API and uses reflection for newer optional behaviour. Folia is not supported.
