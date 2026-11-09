# Changelog

## Unreleased

- Added optional global lockdown DNS routing. Default, Bootstrap and System DNS
  go through the lockdown proxy (WireGuard or SOCKS5) and are blocked when there
  is no proxy.
- The firestack source now lives in `firestack/`. The AAR is built locally into
  `app/libs`.
- Releases are signed locally with the maintainer keystore.
- Fixed a crash when a downloaded blocklist set has gaps in its list ids. The list
  table is now sized by the largest id.
- Blocklist set updated. Safe Search, social network and piracy lists now come from
  HaGeZi. Lists for YouTube, Steam, eBay, Spotify, Disney+/Vimeo, and Telegram/Skype
  are no longer part of the set, so those services are not blocked by it. The social
  network list blocks all social networks, so Snapchat and Facebook/Instagram selections
  now block them too.
