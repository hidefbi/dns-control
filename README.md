# DNS Control

An unofficial, privacy-focused Android DNS and per-app firewall, forked from
RethinkDNS. DNS Control runs a local VPN service that resolves DNS over encrypted
transports and lets you decide which apps may reach the network.

> This is an unofficial fork. It is not affiliated with or endorsed by RethinkDNS,
> BraveDNS, or any DNS provider. See [NOTICE](NOTICE) for details.

## Features

- Encrypted DNS over DNS-over-HTTPS, DNS-over-TLS, ODoH and DNSCrypt. ODoH and
  DNSCrypt servers and relays are not built in; see [DNSCrypt and ODoH servers](#dnscrypt-and-odoh-servers).
- Built-in presets for Cloudflare (standard, Family and Security) and Quad9
  (Secure and DNSCrypt variants). You can add your own resolvers.
- Optional on-device blocklists for ads, trackers, malware and phishing.
- Per-app network control through a local VPN.
- Fail-closed encrypted DNS: if the selected encrypted resolver fails, the app
  does not silently switch to system DNS.
- No Firebase or Google services are configured.
- Connection, DNS query and diagnostic logs are not stored or uploaded.

## Ad, tracker and malware blocking

DNS Control can block known advertising, tracking, malware and phishing domains
at the DNS level. Blocking is optional and is configured on the local blocklists
screen.

### How it works

1. The app downloads a prepared set of blocklist files from the project's
   download host, `https://hidefbi.github.io/rules-old`. The set is built from
   public third-party blocklists (see [Sources and licenses](#sources-and-licenses)).
2. The domain lists are stored on the device as a compressed trie, a tree of
   domain-name labels that can be searched quickly. A rank file and a tag file
   describe the lists, and a basic configuration file holds the default settings.
3. When an app resolves a name, the query is checked against the local trie. A
   match receives a blocked answer; a non-match is resolved by the selected DNS
   resolver as usual.

Matching happens on the device. The lookup does not send the queried name to a
blocking service. Blocking applies to DNS queries only. It does not block traffic
to a server whose IP address an app already has, and it cannot inspect encrypted
content.

### Updates and verification

The app checks for a newer blocklist set approximately every 3 days. The check
sends the app version code (`vcode`) and the timestamp of the currently installed
set (`tstamp`) to `update/blocklists` on the download host. The check only
determines whether an update is available; it does not download anything. The
download is started from the local blocklists screen.

A download first fetches the manifest, `index.json`, and its signature,
`index.json.sig`. Each file that is already installed and whose size and SHA-256
hash match the new manifest is reused and not downloaded again. Only changed files
are fetched. Because `trie` is a single file, any change to the lists requires it
to be downloaded again. The optional Android Download Manager mode, which is off
by default, always downloads the full set.

Before a new set is activated, the app verifies it:

- `index.json` must carry a valid ECDSA P-256 / SHA-256 signature in
  `index.json.sig`, made with the project key. The public key is compiled into
  the app.
- The `blocklists`, `basicconfig`, `rank` and `trie` files must match the size
  and SHA-256 hash recorded in `index.json`.
- The set's sequence number must match the update being installed and must not be
  older than the last accepted set. This prevents rollback to an older set.

If any check fails, the new set is discarded and the current set remains active.

### Download size

The size of the published set changes with each rebuild, so it is not listed
here. The current file sizes are in the
[`index.json` manifest](https://hidefbi.github.io/rules-old/index.json) and are
described in the [rules-old README](https://github.com/hidefbi/rules-old#readme).

The download is written to temporary storage before it is copied into the app's
storage, so free space of roughly the size of the set is needed while an update
is in progress.

### Sources and licenses

The lists are compiled from public blocklists maintained by their respective
authors, including StevenBlack, HaGeZi, OISD, EasyList and EasyPrivacy, The Block
List Project, Disconnect, Peter Lowe and others. The full list of upstream URLs
and groups (Security, Privacy and ParentalControl) is maintained in the
[rules-old README](https://github.com/hidefbi/rules-old#readme). The build pipeline
is documented in [dns-rules-builder](https://github.com/hidefbi/dns-rules-builder).

Each upstream list remains under its own license and terms. The compiler used to
build the files is derived from serverless-dns/blocklists and is licensed under
MPL-2.0. This project does not claim ownership of any upstream list. Check the
upstream license before redistributing a list.

Blocklists can cause false positives. If a site stops working, change the selected
lists in the app.

## DNSCrypt and ODoH servers

The app does not ship with DNSCrypt or ODoH servers or relays. Add them from the
DNSCrypt and ODoH screens using a source of your choice.

- **DNSCrypt**: add a server or relay as an `sdns://` stamp.
- **ODoH**: add a target as an `https://` URL or an `sdns://` stamp (protocol
  0x05). Add a relay as an `sdns://` stamp (protocol 0x85) or an `https://` URL on
  the ODoH screen, then select it. The selected relay is used for ODoH targets.
  Without a relay, the target can see your IP address, and the app shows a warning.

Reference sources:

- DNSCrypt servers: https://github.com/Quad9DNS/dnscrypt-settings/blob/main/dnscrypt/quad9-resolvers.md
- DNSCrypt relays: https://github.com/DNSCrypt/dnscrypt-resolvers/blob/master/v3/relays.md
- ODoH targets: https://github.com/DNSCrypt/dnscrypt-resolvers/blob/master/v3/odoh-servers.md
- ODoH relays: https://github.com/DNSCrypt/dnscrypt-resolvers/blob/master/v3/odoh-relays.md

## Requirements

- Android 6.0 (API 23) or later.
- Android Studio, or a JDK and the Android SDK, to build from source.

## Building

Build the F-Droid flavor with the full feature set:

```
./gradlew :app:assembleFdroidFullDebug
```

The build fetches the `firestack` native library (gomobile AAR) from its published
source. Use `-PfirestackRepo` and `-PfirestackCommit` to pin a specific version.

Run the unit tests with:

```
./gradlew :app:testFdroidFullDebugUnitTest
```

## Releasing (maintainers)

Pushing a version tag builds, signs, verifies and publishes a release through
`.github/workflows/release.yml`:

```
git tag v0.0.677 && git push origin v0.0.677
```

The workflow refuses to publish unless the APK's `versionName` matches the tag and
the APK is signed with the expected certificate. It requires these repository
secrets: `RELEASE_KEYSTORE_BASE64` (the base64-encoded keystore file),
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`. The
keystore is never committed to the repository.

## Changes from upstream

The application ID is `com.hidefbi.dnscontrol`, so this fork installs alongside
the upstream application and is listed under its own name. The Java package name
is unchanged.

See [NOTICE](NOTICE) for the full summary. In short: log storage, log viewers and
bug-report collection are removed; several screens and background features are
removed; the DNS presets are replaced; and encrypted DNS failures no longer fall
back to system DNS.

## Privacy

Connection and DNS query metadata is not persisted, and no log files are written
or uploaded. Encrypted DNS queries are sent only to the resolver you select.

The only other network requests made for blocking are the update check and the
blocklist download described above. Both contact the download host. The update
check sends the app version code and the timestamp of the currently installed
blocklist set.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE). Original
copyright notices are retained in each source file.

## Trademarks

"RethinkDNS" and "BraveDNS" and their logos are trademarks of their respective
owners. This project does not use them to imply endorsement.
