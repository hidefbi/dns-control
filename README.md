# DNS Control

An unofficial, privacy-focused Android DNS and per-app firewall app, forked from
RethinkDNS. It runs a local VPN that resolves DNS over encrypted transports and
lets you decide which apps may reach the network.

> This is an unofficial fork. It is not affiliated with or endorsed by RethinkDNS,
> BraveDNS, or any DNS provider. See [NOTICE](NOTICE) for details.

## Features

- Encrypted DNS: DNS-over-HTTPS, DNS-over-TLS, ODoH and DNSCrypt (ODoH and
  DNSCrypt servers and relays are not built in; see below)
- Built-in presets: Quad9 (Classic and ECS) and Cloudflare; add your own
- Per-app network control through a local VPN
- Fail-closed encrypted DNS: if the selected encrypted resolver fails, the app
  does not silently switch to system DNS
- No Firebase or Google services are configured
- No connection, DNS query or diagnostic logs are stored or uploaded

## DNSCrypt and ODoH servers

This app does not ship with any DNSCrypt or ODoH servers or relays. For
transparency, add them yourself: copy an address from the sources below, then
add it manually in the app on your phone (DNSCrypt and ODoH screens).

为了追求透明，请都自行添加：从下面的链接复制地址，然后在手机上的 App 中手动添加。

- DNSCrypt target servers: https://github.com/Quad9DNS/dnscrypt-settings/blob/main/dnscrypt/quad9-resolvers.md
- DNSCrypt relays: https://github.com/DNSCrypt/dnscrypt-resolvers/blob/master/v3/relays.md
- ODoH target servers: https://github.com/DNSCrypt/dnscrypt-resolvers/blob/master/v3/odoh-servers.md
- ODoH relays: https://github.com/DNSCrypt/dnscrypt-resolvers/blob/master/v3/odoh-relays.md

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

The application ID is `com.hidefbi.dnscontrol`, so this fork installs alongside
the upstream app and is listed under its own name and author. The Java package
name is unchanged.

See [NOTICE](NOTICE) for a summary. In short: log storage, log viewers and bug
report collection are removed, several screens and background features are removed, DNS presets are
replaced, and encrypted DNS failures no longer fall back to system DNS.

## Privacy

Connection and DNS query metadata is not persisted, and no log files are written
or uploaded. Encrypted DNS queries are sent only to the resolver you select.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
Original copyright notices are retained in each source file.

## Trademarks

"RethinkDNS", "BraveDNS" and their logos are trademarks of their respective
owners. This project does not use them to imply endorsement.
