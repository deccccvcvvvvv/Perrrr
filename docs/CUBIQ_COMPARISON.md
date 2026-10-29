# Cubiq reference analysis and parity roadmap

Reference: https://github.com/VivxKumar07/Cubiq-Launcher, commit `289cc076012f5b2a7a58b88ccd355f7e912e1d5e` (inspected 2026-10-29). Cubiq is GPL-3.0; its upstream sources and assets have their own licence/ownership considerations. The reference checkout is not a drop-in replacement for this project: it is a multi-module Zalith-based application with different runtime, account, version, task and UI managers. Directly substituting it would replace the CS Launcher core. The shell uses CS Launcher data and original generated artwork. As of the subsequent adaptation, `ui/theme/CubiqButton.kt` includes the upstream Cubiq button implementation adapted for CS font, sound, haptics and spinner; its GPL-3.0 notice is shipped with the application. Cubiq image files have not been imported.

## Reference experience vs this phase

| Area | Cubiq reference | CS Launcher status |
|---|---|---|
| Landscape Play | Account sidebar, Play/Installations/Skins/Patch Notes/News/Mods/Profile navigation, artwork, centered logo, version picker and green play dock | Reference-inspired shell, dynamic account, profile picker and real core actions; generated artwork and text logo, not pixel-identical. Downloads and Settings remain accessible. |
| Installations | Search, cards, selection, launch and management | Searchable installation rows wired to existing profile creation, selection, install, launch and management. |
| News | Separate news and patch-note screens | Real official Java patch-note feed with refresh and article links. Both destinations currently use that feed; they are not a full copy of Cubiq's separate sources. |
| Animation | Press feedback and rotating/pulsing launch visual | Green button press and rotating busy indicator; honours reduced motion. Other Cubiq animations not yet matched. |
| Accounts | Microsoft, offline, profile/wardrobe | Existing Microsoft, Ely.by and offline account flows preserved. Cubiq skinview3d animation is integrated for PNG preview; Microsoft uploads require explicit confirmation. Offline skins remain preview-only and capes/wardrobe parity is incomplete. |
| Loaders | Advertises Fabric, Forge, NeoForge, Quilt | CS core supports Vanilla/Fabric/Quilt and OptiFine; Forge/NeoForge installation requires installer processor support and is not advertised as working. |
| Mod and content hub | Modrinth/CurseForge mods, modpacks, resource packs, shaders | Existing browser and per-profile content management retained; parity of filters/workflows still needs a feature-by-feature comparison. |
| Renderers and Java | Cubiq-specific renderer stacks and runtimes | Existing CS Java/native stack retained; no claim that Cubiq-only renderers or drivers have been integrated. |
| Multiplayer | Terracotta LAN proxy and server browser | Saved-server add/edit/delete with NBT preservation, backups and on-demand ping are implemented in an app-side adapter using the existing CS core. Cubiq/Terracotta LAN proxy and direct joining are not integrated. LAN proxy requires networking, security, lifecycle and device testing. |
| Controls | touch layouts, editor, gamepad, gyro | Existing control editor, multi-touch and controller support retained; gyro and exact Bedrock control parity not verified. |

## Next engineering steps

1. Audit every screen and interaction against the upstream reference on real devices; reconcile spacing, responsiveness, assets, press/haptic/audio and reduced-motion behavior.
2. Specify and implement real skin preview/upload with account-type restrictions and safe file handling; test 2D/3D previews and offline behaviour independently.
3. Implement Forge and NeoForge installer processors in the CS core, with isolated paths, validation, cancellation and reproducible tests across versions.
4. Design a separately reviewed LAN/server feature, including session lifecycle, authentication, permission/privacy boundaries, platform compatibility and two-device tests.
5. Verify renderer, control, gamepad and gyro compatibility on physical Android hardware; do not expose unsupported choices as available.
6. Run GitHub Actions plus real-device smoke tests for login, install, play, downloads, profile changes, content management and failure recovery. A successful CI build is not a device test.

## UI consistency and skin preview update

A follow-up unifies the default pixel-theme surface and button palette across existing screens and adds an in-app, account-backed 2D skin preview. The Minecraft.net link is still used for changing Microsoft skins; local/offline skin installation and the upstream 3D wardrobe are not implemented. The sidebar also keeps installed content, worlds, screenshots and recordings reachable. The Mods browser now has a hub-style heading and a shortcut to installed mods. These are adaptations of existing CS screens, not a pixel-exact recreation of every Cubiq screen.

## Direct Cubiq component adaptation

The original Cubiq Minecraft button style, bevel and press handling are adapted into `CubiqButton.kt` and used by the default pixel theme and the home Play button. The CS click sound, haptics, font and loading indicator replace Cubiq-specific dependencies. The launcher name and logo remain CS Launcher. The GPL-3.0 text is included under `app/src/main/assets/licenses/` and the upstream commit is recorded in the source header. Other upstream UI screens, animations and features have not yet all been ported.

## Skin and server integration update

Cubiq's skinview HTML and animation JavaScript at the pinned revision, together with skinview3d/three.js, are bundled for a local WebView 3D preview; the app keeps CS branding. The viewer accepts validated 64×64 or 64×32 PNG files and uses an offline-only WebView with no JavaScript bridge. A Microsoft skin upload is available only after confirmation. The official API upload and WebView interaction require device/account testing. Offline skin injection and cape wardrobe are not implemented.

The multiplayer screen can add, edit, delete and ping the selected profile's server entries. The app-side adapter keeps unknown NBT entries, creates a backup and rejects stale files; editing is disabled during launch/gameplay. Joining still happens inside Minecraft. LAN proxy is not implemented.
