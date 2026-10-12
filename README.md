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
- Optional on-device blocklists for ads, trackers, malware and phishing (see below)
- Per-app network control through a local VPN
- Fail-closed encrypted DNS: if the selected encrypted resolver fails, the app
  does not silently switch to system DNS
- No Firebase or Google services are configured
- No connection, DNS query or diagnostic logs are stored or uploaded

## Ad, tracker and malware blocking

DNS Control can block known ad, tracker, malware and phishing domains at the
DNS level. Blocking is optional and is set up from the local blocklists screen.

### How it works

1. The app downloads a set of prepared blocklist files from the project's
   download host (`https://hidefbi.github.io`). These files are built from
   public third-party blocklists (see [Sources](#sources-and-licenses)).
2. The domain lists are stored on the phone as a compressed trie, a tree of
   domain-name labels that can be searched quickly. A small rank file and a
   tag file describe the lists, and a basic configuration file holds settings.
3. When an app asks for a name, the app checks the query against the local
   trie. A match gets a blocked answer; a non-match is resolved by your
   selected DNS resolver as usual.

Matching happens on the device. The lookup does not send the queried name to
a blocking service. Blocking is applied to DNS queries only; it does not block
traffic to a server whose address an app already has, and it cannot see inside
encrypted content.

### Updates

The app checks for a newer blocklist version about every 3 days. The check
sends the app version code and the timestamp of your current lists to the
download host. The app then tells you that an update is available; the download
starts only when you choose it.

Each download includes an `index.json` file and an `index.json.sig` signature
file. This fork does not verify the signature in its own code, so treat the
download host as the trust point.

### Download sizes

These are the sizes of the files published on the download host at the time of
writing. They change whenever the lists are rebuilt.

| File | Size | Purpose |
|---|---|---|
| `trie` | about 69 MB (71,423,522 bytes) | Compressed domain trie used for matching |
| `rank` | about 5.8 MB (6,026,360 bytes) | Rank data for the lists |
| `blocklists` | about 42 KB (42,158 bytes) | List of included blocklists and tags |
| `index.json` | about 31 KB (31,459 bytes) | Index of the files and their versions |
| `index.json.sig` | 96 bytes | Signature for the index |
| `basicconfig` | 353 bytes | Basic configuration |

A full download is about 75 MB, so use Wi-Fi or check your data plan first.

### Sources and licenses

The lists are compiled from public blocklists maintained by their own authors,
including StevenBlack, HaGeZi, OISD, EasyList and EasyPrivacy, The Block List
Project, Disconnect, Peter Lowe, and many others. The full table of upstream
URLs and groups (Security, Privacy and ParentalControl) is in the
[download host README](https://github.com/hidefbi/hidefbi.github.io/blob/main/README.md).

Each upstream list keeps its own license and terms. The compiler that builds
the files is MPL-2.0 licensed (serverless-dns/blocklists). This fork does not
claim ownership of any upstream list. Before redistributing a list, check its
upstream license.

Blocklists can cause false positives. If a site breaks, change the selected
lists in the app.

## DNSCrypt and ODoH servers

This app does not ship with any DNSCrypt or ODoH servers or relays. For
transparency, add them yourself: copy an address from the sources below, then
add it manually in the app on your phone (DNSCrypt and ODoH screens).

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

Blocking downloads and the update check described above are the only other
network requests made for blocking. The update check sends the app version code
and the timestamp of your current lists.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
Original copyright notices are retained in each source file.

## Trademarks

"RethinkDNS", "BraveDNS" and their logos are trademarks of their respective
owners. This project does not use them to imply endorsement.
