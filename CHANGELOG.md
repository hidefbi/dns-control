# Changelog

## Unreleased

- Added optional global lockdown DNS routing. Default, Bootstrap and System DNS
  go through the lockdown proxy (WireGuard or SOCKS5) and are blocked when there
  is no proxy.
- The firestack source now lives in `firestack/`. The AAR is built locally into
  `app/libs`.
- Releases are signed locally with the maintainer keystore.
