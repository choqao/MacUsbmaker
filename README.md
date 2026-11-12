# Mac USB Builder

Android app (no root) with two modes:

1. **macOS Recovery** - downloads BaseSystem.dmg + chunklist from Apple (same protocol as OpenCore's
   macrecovery.py) into `com.apple.recovery.boot/` on a **FAT32** USB drive, and can unpack an OpenCore
   `EFI` .zip onto the drive root. Uses the Storage Access Framework, so no raw USB access is needed.
2. **Flash .img** - writes a raw image to the drive with SCSI over USB Bulk-Only Transport (UsbManager).

## Build
Open this folder in Android Studio (Ladybug or newer). It generates the Gradle wrapper on sync. Run on a
device with USB host / OTG support. Use a *powered* hub.

## Notes
- Format the stick as FAT32 first (any phone/PC tool) for mode 1.
- Board IDs in `AppleRecoveryClient.kt` come from memory of OpenCore's boards.json; check them against the
  current file. Use the "Custom board ID" box to override.
- Mode 1 output boots through OpenCore (Hackintosh). It is not a stock installer for real Macs.
- Not yet implemented: GPT + FAT32 formatter, so the drive must be pre-formatted.
- Apple's endpoints are plain HTTP; the network security config allows cleartext for apple.com only.
