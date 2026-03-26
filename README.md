# treadmill-bridge

An Android app that bridges NordicTrack/ProForm iFit treadmills to third-party
fitness apps (such as Zwift) via the FTMS and DIRCON protocols.

The app communicates directly with the treadmill's motor controller over USB
using the FitPro1 protocol, then exposes real-time telemetry (speed, incline)
and accepts control commands over the network.

## How It Works

1. **USB** — Connects to the treadmill motor controller (VID 8508, PID 2) via
   the FitPro1 protocol over USB HID.
2. **FTMS/DIRCON** — Serves a TCP-based DIRCON server that speaks the Fitness
   Machine Service (FTMS) protocol, allowing apps like Zwift to read telemetry
   and send control commands.
3. **mDNS** — Advertises the DIRCON service on the local network so fitness
   apps can discover it automatically.

## Interoperability Notice

This is an **independent interoperability project**. It is not affiliated with,
endorsed by, or connected to iFit, NordicTrack, ICON Health & Fitness, or any
of their subsidiaries.

The FitPro1 protocol implementation was developed through reverse engineering
for the sole purpose of interoperability with independently created software,
as permitted under DMCA 1201(f) and established case law (Sega v. Accolade,
Sony v. Connectix). This project enables owners of compatible treadmill
hardware to use that hardware with third-party fitness applications.

Similar interoperability projects exist for other fitness equipment (e.g.,
[qdomyos-zwift](https://github.com/cagnulein/qdomyos-zwift)).

## Disclaimer

This software is provided as-is. Users are solely responsible for ensuring
compatibility with their own equipment and compliance with applicable laws and
terms of service in their jurisdiction. Use at your own risk.

## Protocol Documentation

- [FitPro1 Protocol Specification](docs/fitpro1-protocol.md)

## License

[MIT](LICENSE)
