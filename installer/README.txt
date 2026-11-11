Tracker-Rust autotracker @VERSION@
Unofficial modified build of Tracker 6.3.5 (Open Source Physics), Windows x64.

Start
  Start menu or desktop: Tracker-Rust.
  In the Autotracker window, tick "Fast" to use this build (native matcher, read-ahead
  decoding, pipelined tracking). Unticked, Tracker runs its original code.
  Autotracker key frames and settings are saved in .trk/.trz files and restored on open.

Memory
  Tracker-Rust uses half of the physical RAM (1-16 GB). To change it, set the
  environment variable TRACKER_MEMORY_MB (for example 4096).

Remove
  Settings > Apps > Installed apps > Tracker-Rust, or run Uninstall.exe in this folder.

Included
  OpenJDK 21.0.5 runtime (jre\, GPL v2 with Classpath Exception, see jre\legal),
  Xuggle video library (Xuggle\), both as shipped with Tracker 6.3.5.

Source, documentation and benchmarks:
  https://github.com/laplacian-n/tracker-rust-autotracker

License: GNU GPL v3.0 or later (see LICENSE). Tracker and the Open Source Physics
library are (c) Douglas Brown, Wolfgang Christian and contributors, GPL v2 or later;
their source is at https://github.com/OpenSourcePhysics/tracker (tag version_6.3.5).
