# DAVIX – MandraKodi source integration

The Player Lite module contains a MandraKodi-compatible catalog adapter.

## Supported inputs
- M3U playlists with EXTINF metadata (tvg-id, tvg-name, tvg-logo and group-title).
- JSON arrays named channels or stations.
- JSON groups containing stations or channels.
- JSON menu entries only when they expose an explicit direct HTTP(S) media URL.

The adapter normalizes metadata, creates stable source-scoped IDs and removes duplicate IDs within one import. Network loading must run on a worker thread.

## Upstream behavior and configuration
The public MandraKodi repository exposes a Kodi add-on menu and several separate data files. Its launcher requests the remote menu with a MandraKodi-specific User-Agent containing the add-on version, configured password and generated device identifier. The menu may contain folders, scripts, resolver actions and external links; it is not necessarily a flat channel playlist.

DAVIX therefore does not execute Kodi Python or resolver actions. Configure a direct M3U or JSON export URL that the user is entitled to access. No default feed URL is hardcoded because the current upstream menu is credential/device-gated and may contain navigation actions rather than channel records.

## Playback boundary
Only ordinary HTTP(S) media URLs are imported. DRM, paywall, authentication and access restrictions are not bypassed. Media3 remains the playback engine for supported HLS, DASH and Smooth Streaming content.

## Usage
Run this from a worker thread:

    val adapter = MandraKodiSourceAdapter()
    val channels = adapter.load(MandraKodiSourceConfig(catalogUrl = configuredFeedUrl))

Merge the returned records into the existing catalog transactionally. Preserve user favorites, custom names, custom logos and EPG mappings as local overrides.
