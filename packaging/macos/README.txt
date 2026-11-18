Tracker-Rust autotracker @VERSION@ for macOS
Unofficial modified build of Tracker 6.3.5 (Open Source Physics).

Install
  Drag Tracker-Rust onto the Applications folder.

First start
  The app is not signed by Apple, so macOS blocks the first start:
  - macOS 15 and later: open it once, then System Settings > Privacy & Security,
    scroll down to "Tracker-Rust was blocked" and click Open Anyway.
  - macOS 14 and earlier: right-click (Control-click) Tracker-Rust > Open > Open.
  Or in Terminal:  xattr -dr com.apple.quarantine /Applications/Tracker-Rust.app

Apple silicon (M1, M2, M3, M4 ...)
  Tracker's video library (Xuggle) is made for Intel Macs only, so Tracker-Rust
  runs through Rosetta 2. If Rosetta is missing, Tracker-Rust offers to install it.

Use
  In the Autotracker window, tick "Fast" to use the fast autotracker; unticked,
  Tracker runs its original code. Coordinate System > Calibrate from Pattern calibrates
  from a printed checkerboard (boards in docs/calibration of the source repository).

Memory
  Half of the physical RAM (1-16 GB). To change it, start from Terminal with
  TRACKER_MEMORY_MB=4096 /Applications/Tracker-Rust.app/Contents/MacOS/Tracker-Rust

Included
  Eclipse Temurin (OpenJDK) 21 runtime for Intel Macs (GPL v2 with Classpath Exception,
  see Contents/Resources/jre/Contents/Home/legal), Xuggle (GPL/LGPL) as shipped with
  Tracker 6.3.5.

Source, documentation and benchmarks:
  https://github.com/laplacian-n/tracker-rust-autotracker

License: GNU GPL v3.0 or later. Tracker and the Open Source Physics library are
(c) Douglas Brown, Wolfgang Christian and contributors, GPL v2 or later; their source
is at https://github.com/OpenSourcePhysics/tracker (tag version_6.3.5).
