# FlashLink 2.0 — Android optical transfer prototype

## Requirements and operation
Two Android phones use only their rear flashlight (TX) and rear camera (RX). No Wi-Fi, Bluetooth, mobile data, external LED, photodiode, or other hardware.

Manual half-duplex:
1. On receiver phone, tap START RECEIVER. Receiver forces its torch off.
2. On sender phone, send a text or choose any file.
3. To transfer in reverse, manually stop/leave receiver and switch roles.

## Features
- Kotlin Android app with CameraX preview and image analysis.
- Center-region luma measurement and manual brightness threshold.
- Configurable OOK bit duration (100–600 ms).
- Optical start pulse + guard interval for packet start.
- Packet fields: magic, type, transfer ID, chunk index, chunk count, payload length.
- CRC-16/CCITT packet validation.
- Arbitrary file picker and destination picker.
- 128-byte chunks; 2 MiB prototype size guard.
- GitHub Actions debug APK workflow.

## Build
Upload the contents of this folder (including hidden `.github`) to repository root. Open GitHub Actions, choose Build FlashLink 2.0 APK, run workflow, then download artifact `FlashLink-2.0-debug-apk`. Extract `app-debug.apk` and install on both phones.

## First test
Use dim indoor lighting at 0.5–1 m. Set both phones to 600 ms bit duration. Start receiver first. Adjust threshold so the lit reading is above threshold and dark reading below. Send a short text. Only after that test a tiny file. Reverse roles manually for the other direction.

## Limitations — read before testing
This is a prototype and has not been built or physically validated on Redmi Note 7 Pro. It is not a production high-speed modem. Android torch APIs are not designed for rapid modulation; camera frame rate, exposure, torch latency and MIUI camera HAL vary. The receiver uses fixed bit timing after a long start pulse; it has no clock recovery, Manchester coding, ACK/NACK, retransmission, resume, encryption, or recovery of missing packets. A CRC detects a damaged packet but does not repair it. Files are sent in 128-byte chunks, so arbitrary file transfer may take an impractically long time. 20 m and fast transfer are not guaranteed. Test at short range first.
