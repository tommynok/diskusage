<p align="center">
  <img src="extra/icon512x512.png" width="128" height="128" alt="DiskUsage Logo">
</p>

<h1 align="center">DiskUsage-NG</h1>

<p align="center">
  <b>English</b> | <a href="README.ru.md">Русский</a>
</p>

> A modernized fork of the classic [DiskUsage](https://github.com/IvanVolosyuk/diskusage) application by Ivan Volosyuk. Sincere gratitude to the original author for creating an indispensable Android utility and releasing it under the GNU General Public License v2!

---

## About the application

DiskUsage is a high-performance Android storage visualizer. Unlike standard file managers with flat lists, it renders a visual proportional map of your storage where directories and files are shown as proportional blocks. Zoom in, inspect what is eating your storage, and clean up space safely.

**Core features:**

- Visual proportional treemap representation of internal storage, SD cards, and USB drives
- Deep directory zoom and fluid interactive navigation
- Safe deletion with item inspection and system-level protections
- Reveal files and folders directly in supported file managers (MiXplorer, Total Commander, Solid Explorer, Material Files)
- 100% offline, zero internet permissions, zero ads, zero tracking

---

## Differences from the original

### ✦ Added

- **Universal Root support** — verified compatibility with modern root solutions (KernelSU, APatch, SukiSU, Magisk, SuperSU, and LineageOS `su`)
- **Rootless elevated access via Shizuku & Porter** — scan root filesystems (`/`, `/data`, `/system`) without rooting your device:
  - Supports [Shizuku](https://shizuku.rikka.app/)
  - Supports [Porter](https://github.com/d4rken-org/porter) (daemon by darken)
- **Unified "Permissions & Access" control sheet** — interactive live status indicators (🟢 / 🟡 / ⚪) for all Android system permissions and elevated providers, with direct action buttons and clear explanations
- **Direct native library execution** — eliminates temporary script unpacking to `/data/local/tmp` for security and clean cancellation
- **Dynamic semantic versioning** — automated CI build numbering with clear version names in the About dialog

### ✦ Improved

- Fully compatible with modern Android releases (Android 11 through 15+)
- Safe folder reveal using `file://` exposure bypass and an adaptive file manager selector
- Modernized UI with edge-to-edge support, light status bars, and rounded bottom-sheet dialogs
- Streamlined package size: stripped unused third-party translations, keeping only English, Russian, and Ukrainian

---

## License

This project inherits the license of the original [DiskUsage](https://github.com/IvanVolosyuk/diskusage) project.
Distributed under the terms of the GNU General Public License v2 or later. See [COPYING.txt](COPYING.txt) for details.
