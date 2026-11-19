<p align="center">
  <img src="extra/icon512x512.png" width="128" height="128" alt="DiskUsage Logo">
</p>

<h1 align="center">DiskUsage-NG</h1>

<p align="center">
  <b>English</b> | <a href="README.ru.md">Русский</a>
</p>

> A modernized fork of the classic [DiskUsage](https://github.com/IvanVolosyuk/diskusage) application by Ivan Volosyuk. Sincere gratitude to the author for creating an indispensable Android utility and publishing the source code!

---

## About the application

DiskUsage is a high-performance Android storage visualizer. Unlike standard file managers with flat lists, it renders a visual proportional map of your storage (treemap) where directories and files are shown as proportional blocks. Zoom in, inspect what is eating your storage, and clean up space safely.

**Core features:**

- Visual proportional treemap representation of internal storage, SD cards, and USB drives
- Deep directory zoom and fluid interactive navigation
- Safe deletion with detailed item properties and accidental removal prevention
- Quick reveal of files and folders in popular file managers (MiXplorer, Total Commander, Solid Explorer, Material Files)

---

## Differences from the original

### ✦ Added

- **Light theme**
- **Universal Root** — verified compatibility with modern root solutions (KernelSU, Magisk, etc.)
- **Rootless scanning via Shizuku & Porter** — analysis of system partitions (`/`, `/data`, `/system`) without kernel modification:
  - Shizuku support
  - [Porter](https://github.com/d4rken-org/porter) support (service by the author of SD Maid)
- **"Permissions & Access" control center** — a unified bottom sheet with indicators (🟢 / 🟡 / ⚪) for system permissions and privileges, check buttons, and helpful tips

### ✦ Improved

- Full compatibility with modern Android versions (Android 11–15+)
- Opening folders in third-party file managers without `FileProvider` crashes
- Modernized UI: clean bottom sheets instead of outdated legacy popup windows

---

## License

This project inherits the license of the original [DiskUsage](https://github.com/IvanVolosyuk/diskusage) project.
Distributed under the terms of the GNU General Public License v2 or later. See [COPYING.txt](COPYING.txt) for details.
