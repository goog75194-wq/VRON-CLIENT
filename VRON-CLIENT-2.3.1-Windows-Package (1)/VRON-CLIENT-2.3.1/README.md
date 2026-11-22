# VRON CLIENT — Black Edition 2.3.1 (Java)

A Windows-oriented Minecraft: Java Edition launcher built with Java 21+ and Swing. Version 2.3.1 keeps the actual Minecraft install/launch engine and the live Modrinth, CurseForge, Fabric, Forge, and Microsoft sign-in integrations from 2.2, with a redesigned premium interface and expanded mod/profile management.

## 2.3.1 visual refresh

- New custom VRON raven crest logo replaces the placeholder letter mark in the sidebar, home hero, window title bar and Windows installer icon. The transparent PNG is bundled inside the JAR; the multi-size ICO is used by jpackage.
- Refined premium Black Edition home screen with animated gradient/ring artwork, selected Minecraft profile/build/RAM status, and direct actions.
- Mod catalogue uses project icons, descriptions, author and download counts in rich cards; icons are loaded asynchronously so the UI remains responsive.
- Catalogue categories: optimization, graphics/shaders, interface, minimaps, world/content, and libraries. Category filtering is performed against the returned project metadata.
- Persistent favourites are stored in local launcher settings, sorted to the top, and can be filtered as a separate category.
- Launcher-managed installed mods are tracked in each build's `.vron-installed-mods.json`. The update action checks compatible Modrinth/CurseForge releases and replaces managed mod JARs. Manually added JARs are not automatically updated because the launcher has no reliable project identity for them.
- Fabric and Forge profile manager scans the actual installed version metadata, lets the user select a profile, and can remove a non-active loader profile.
- Windows EXE script and GitHub Actions artifact naming/version updated to 2.3.1.

## Marketplace integrations

### Modrinth

Uses the official Modrinth API v2 for project search and compatible versions. Search filters by Minecraft version and selected loader. Required dependencies are installed recursively. Downloaded files are checked against SHA-512 or SHA-1 hashes.

### CurseForge

Uses the official CurseForge API v1 with `x-api-key` authentication, Minecraft game ID `432`, and mod class ID `6`. A personal API key is required in Settings. The key is not bundled with the launcher.

## Minecraft / loader core retained

- Official Mojang version manifest and real client, libraries, natives, assets, and Java runtime downloads.
- SHA-1 validation for Mojang downloads; inherited metadata and conditional JVM/game arguments.
- Actual Minecraft child process launch, console output, and process-exit tracking.
- Separate game directories per build under `%USERPROFILE%\\.vron\\instances\\<profile>`.
- Fabric Meta API version selection and Fabric installer integration.
- Official Forge Maven metadata, installer download/execution, and generated profile detection.
- Microsoft Device Code OAuth, Xbox Live, XSTS, Minecraft Services login, and Java profile lookup. Requires a public Microsoft Entra application Client ID with Device Code Flow enabled.
- Local mod manager supports adding, enabling/disabling, and removing JAR files.

## Build and test on Windows

Requirements: Windows 10/11, full JDK 21+, and WiX Toolset 3.14.

```bat
build.bat
test.bat
build_exe.bat
```

The EXE is created in `release\\VRON CLIENT-2.3.0.exe`. Native EXE packaging must run on Windows; it cannot be produced by the Linux build environment used for this source build.

## GitHub Actions EXE build

`.github/workflows/windows-exe.yml` runs on `workflow_dispatch` or a pushed `v*` tag. It compiles the launcher, runs the regression suite, installs WiX Toolset 3.14, packages the Windows EXE, and uploads it as `VRON-CLIENT-2.3.1-Windows-EXE`. Push this project to a GitHub repository and run the workflow to produce the installer.

## Validation and limitations

- Compiled with Java 21 (`javac --release 21 -Xlint:all`).
- Dependency-free regression suite passes 7/7 tests: JSON round-trip, Fabric profile discovery, inherited library merge, OS argument rules, native extraction, and local HTTP download/hash verification.
- The current environment has no outbound DNS/network access and runs Linux. Live Modrinth/CurseForge calls, live Fabric/Forge installs, Microsoft sign-in, and a real game session could not be exercised here. Windows EXE packaging was prepared but not executed here. No claim is made that these live flows were end-to-end tested in this environment.

## Data directory

`%USERPROFILE%\\.vron` stores settings, builds, Minecraft libraries/assets, cached installers, and runtime files. `launcher.json` includes local preferences and the user-supplied CurseForge API key; protect this file and do not share it.
