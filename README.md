# DNS Control

An unofficial, privacy-focused Android DNS and per-app firewall app, forked from
RethinkDNS. It runs a local VPN that resolves DNS over encrypted transports and
lets you decide which apps may reach the network.

> This is an unofficial fork. It is not affiliated with or endorsed by RethinkDNS,
> BraveDNS, or any DNS provider. See [NOTICE](NOTICE) for details.

## Features

- Encrypted DNS: DNS-over-HTTPS, DNS-over-TLS, ODoH and DNSCrypt
- Built-in presets: Quad9 (Classic and ECS) and Cloudflare; add your own
- Per-app network control through a local VPN
- Fail-closed encrypted DNS: if the selected encrypted resolver fails, the app
  does not silently switch to system DNS
- No Firebase or Google services are configured
- Connection and DNS query logs are not stored; only a local event log is kept
  in the app database

## Requirements

- Android 6.0 (API 23) or newer
- Android Studio, or a JDK and the Android SDK, to build from source

## Building

```
./gradlew :app:assembleFdroidFullDebug
```

The build downloads the `firestack` native library (gomobile AAR) from its
published source. Set `-PfirestackRepo` and `-PfirestackCommit` to pin a version.

Run unit tests with:

```
./gradlew :app:testFdroidFullDebugUnitTest
```

## Changes from upstream

See [NOTICE](NOTICE) for a summary. In short: log storage and log viewers are
removed, several screens and background features are removed, DNS presets are
replaced, and encrypted DNS failures no longer fall back to system DNS.

## Privacy

Connection and DNS query metadata is not persisted. The local event log stays on
the device. Encrypted DNS queries are sent only to the resolver you select.

## Support

If this project is useful to you, you can support its development:

- GitHub: https://github.com/hidefbi/dns-control
- BTC: `bc1q34tccx8ttkqdpaywugmdvhamqmc9uhh9qttqn7`
- ETH: `0x21ac40d9afec1e6fd3c0abe730911da4a03d1d98`

Donations are optional and do not grant any features or support obligations.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
Original copyright notices are retained in each source file.

## Trademarks

"RethinkDNS", "BraveDNS" and their logos are trademarks of their respective
owners. This project does not use them to imply endorsement.
