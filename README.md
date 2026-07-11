<p align="center">
  <img src="src/main/resources/assets/mommymods/icon.png" alt="MommyMods" width="128">
</p>

<h1 align="center">MommyMods 26.1.2</h1>

<p align="center">
  <a href="https://github.com/Zytrm/MommyMods/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/Zytrm/MommyMods/build.yml?style=for-the-badge&logo=github" alt="Build"></a>
  <a href="https://github.com/Zytrm/MommyMods/releases"><img src="https://img.shields.io/github/downloads/Zytrm/MommyMods/total?style=for-the-badge&logo=github" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Zytrm/MommyMods?style=for-the-badge" alt="License"></a>
</p>

> Focused fishing utilities for Hypixel SkyBlock, built with Fabric and Kotlin.

---

## Installation

1. Install [Fabric for Minecraft 26.1.2](https://fabricmc.net/use/installer/).
2. Install [Fabric API](https://modrinth.com/mod/fabric-api/versions?g=26.1.2) and [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin).
3. Download the latest MommyMods `.jar` from [Releases](https://github.com/Zytrm/MommyMods/releases).
4. Place all three `.jar` files in your `.minecraft/mods` folder.
5. Launch Minecraft with the Fabric profile and type `/mm` in chat.

## Features

- **Hide Fishing Line** — hides the line between your rod and bobber without hiding the bobber.
- **LouderCatch** — plays a configurable alert exactly when a fish is ready to reel, with volume up to 20x.
- **FishingPartyHelper** — checks Fishing 45, Silver Trophy Hunter, Looting V, Bloodshot belt, and Jawbus eligibility when players join.
- **Jawbus Finder** — shows a compact alert when a non-party player dies to Jawbus in your lobby.
- **Looting V Message** — sends one configurable reminder when you spawn a Jawbus.

## Configuration

Open the compact fishing menu with any of these commands:

```text
/mm
/mommymods
/mommy mods
```

Left-click a feature to toggle it. Right-click a configurable feature to open its options. Settings are saved to `config/mommymods.json`.

<details>
<summary><strong>Debug commands</strong></summary>

Debug tools are opt-in and never auto-kick or send the preview message.

```text
/mmcatchdebug
/mmpartydebug self
/mmpartydebug profile <player>
/mmpartydebug status
/mmpartydebug message
```

</details>

<details>
<summary><strong>Detection notes</strong></summary>

- Features activate only on `hypixel.net` and its subdomains.
- LouderCatch follows the local hook lifecycle and confirms the associated `!!!` fishing timer marker before alerting.
- FishingPartyHelper uses a narrow readiness service and falls back to visible in-game gear when profile data is unavailable. Unknown values are never treated as failures for auto-kick.
- A player can Jawbus only with Fishing 45 or higher and Silver Trophy Hunter.
- The belt check distinguishes no equipped Gillsplash/Finwave from a relevant belt without Bloodshot.
- Jawbus alerts and chat reminders use narrow Hypixel messages with per-event cooldowns to avoid duplicates.

</details>

## Building

Requires JDK 25.

```bash
./gradlew build
```

Windows PowerShell:

```powershell
./gradlew.bat build
```

The distributable JAR is written to `build/libs/`.

## Contributions

Issues and pull requests are welcome. Keep changes focused, test against Minecraft 26.1.2, and include a clear description of user-facing behavior.

## License

MommyMods is licensed under the [MIT License](LICENSE).
