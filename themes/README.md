# Themes

A theme is a folder with a `config.json` and, optionally, a `wallpaper.jpg`. Pushing one changes the
launcher's dock, layout and look on the TV without rebuilding the APK: the launcher reads
`config.json` as an overlay over its built-in `config.xml` the next time it starts.

```bash
TV_HOST=192.168.1.50 tools/theme.sh themes/aurora          # push a theme and restart Home
TV_HOST=192.168.1.50 tools/theme.sh --reset                # back to the built-in config.xml
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost 192.168.1.50 themes\aurora
```

| Theme | What it does |
|---|---|
| `default/` | The built-in look written out as JSON, as a starting point for your own |
| `aurora/` | A dark aurora wallpaper (teal and violet on navy, darker at the bottom) with a matching background colour |
| `six-columns/` | Six smaller tiles per row and one grid row visible under the dock |

## Keys

Every key is optional; a missing key keeps the value from `config.xml`, an unknown or wrongly typed key
is ignored, and a file that doesn't parse is ignored as a whole, so a theme can never break Home.

| Key | Type | Meaning |
|---|---|---|
| `dock_apps` | list of package names | The dock row, in order; missing apps are skipped. Once someone arranges the apps on the TV (hold OK, then Move), their order is used instead. A phone-only app named here (no TV entry, e.g. the phone Play Store some boxes ship) is shown too, with its icon; phone-only apps not named stay hidden |
| `hidden_apps` | list of package names | Never shown as tiles |
| `live_tv_package`, `live_tv_action` | string | The Live TV app and, if it has no leanback entry, the action to start it with |
| `source_panel_service`, `source_panel_action` | string | The TV's own source panel, for sets with no TvInputManager |
| `input_action` | string | Action sent with an HDMI/AV row's channel URI when nothing handles the standard `VIEW` (XGIMI: `com.xgimi.action.hdmiPlayer`) |
| `columns` | 1 to 8 | Tiles per row; tiles shrink to fit from 5 up |
| `rows_below_dock` | 0 to 3 | Grid rows left visible under the dock |
| `shamsi_date` | bool | Show the Solar Hijri date above the Gregorian one |
| `auto_location` | bool | Use Android's current location to reverse-geocode a kabupaten/kota, then fetch Indonesian prayer times from the [myQuran API](https://api.myquran.com/doc). The city and daily data are cached on the TV. Location updates run while Home is open. Default `true` |
| `weather` | bool | Show the current temperature, conditions and detected city next to the date. Weather coordinates are rounded to about 1 km and sent to [Open-Meteo](https://open-meteo.com/en/docs). Default `true` |
| `download_masjid` | bool | Download the photos listed in `themes/islamik/masjid.json` from Wikimedia Commons to local app storage for the screensaver. Home wallpaper photos are streamed into memory and are not saved locally. Default `true` |
| `download_azan` | bool | Download the theme's default `azan.mp3` and `azan-subuh.mp3` from this repository to local app storage when those files are missing. Existing theme audio is kept. Default `true` |
| `azan` | bool | With a resolved city and prayer times: at each prayer time, a full-screen azan screen (a gold crescent with rings of light) over the screensaver or Home, playing `azan.mp3` from the theme folder (`azan-subuh.mp3` for Subuh, if present) or silent for 90 seconds without one. Any button ends it. Default `true` |
| `azan_over_apps` | bool | With `azan` on: at a prayer time while another app is in front (YouTube, Netflix, Live TV), a band across the lower third of the screen with the prayer's name and the azan; the app underneath is asked to pause. Any button stops it. Needs "display over other apps", which the install scripts grant (`appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow`). Default `true` |
| `masjid_home` | bool | Use photos from `themes/islamik/masjid.json` as Home wallpapers, rotating every three minutes with the bundled Kaaba image as the offline fallback. Set `false` to use the theme's `wallpaper.jpg` or `wallpapers/` files instead. Default `true` |
| `module_cards` | bool | Look for module cards and ask about them (`true`); `false` turns the feature off |
| `reduced_motion` | bool | Calmer motion for weak GPUs: stiff highlight spring, no tile overshoot, short fades, no entrance replay |
| `vpn_app` | string | App the network pill opens when a VPN is configured; also shows a red "VPN off" badge while no VPN is up |
| `show_home_settings_shortcut` | bool | The top-bar Home icon for choosing the default launcher |
| `show_network_pill` | bool | The Wi-Fi / Ethernet pill in the top bar; `false` hides it |
| `show_inputs` | bool | The Inputs shortcut in the top bar; `false` hides it |
| `show_dock_tray` | bool | Glass panel behind the pinned app row; set `false` to show the apps without the tray |
| `show_live_tv` | bool | The Live TV row in the Inputs menu; `false` leaves only the HDMI and AV inputs |
| `background_color` | `#RRGGBB` | Colour behind everything; black by default |
| `wallpaper` | bool | When `masjid_home` is `false`, show `wallpaper.jpg` from the theme folder (`true`) or ignore it (`false`) |
| `wallpaper_seconds` | 5 to 86400 | Seconds between local theme pictures when `masjid_home` is `false`; when set, it wins over `wallpaper_minutes` |
| `wallpaper_minutes` | 0 to 1440 | Minutes between local theme pictures when `masjid_home` is `false` and the theme has a `wallpapers/` folder; 0 changes only when Home starts. Default 30 |
| `cache_clean_days` | 0 to 90 | Days between clearing the TV's app caches, which the TV does itself (default 15); `0` turns it off. See [docs/cache-clean.md](../docs/cache-clean.md) |
| `places` | bool | Legacy setting retained for older theme files; current Home uses the curated mosque list when `masjid_home` is `true`, and local theme pictures when it is `false` |

Automatic location uses Android's available location providers while Home is open. Reverse geocoding and prayer-city
matching happen on the TV; the weather request sends coordinates rounded to two decimal places to Open-Meteo.
The prayer request sends the matching city ID to myQuran. If location permission or a provider is unavailable,
the prayer and weather rows stay hidden until a location is available.

## Wallpapers

When `masjid_home` is `false`, `wallpaper.jpg` or the sorted images in `wallpapers/` are drawn full screen
behind the launcher, centre-cropped. Keep them dark, 1920×1080 and
under about 300 KB: the text, the hero name and the dock band are designed for black, and a 1 GB TV
decodes the picture into memory on every Home start. Pictures larger than the screen are downsampled.

## Reduced motion

`"reduced_motion": true` keeps the look but calms the movement: the highlight spring is stiff instead
of bouncing, tiles zoom without overshoot, fades are shorter, and Home does not replay the tile
entrance when you come back to it. Use it on TVs whose GPU cannot keep up; a MediaTek MT5895 on
Android 10 is the known case. The default, `false`, keeps the normal motion.

```json
{ "reduced_motion": true }
```

## Adding a theme

Open a PR with a new folder here, a one-line row in the table above and a screenshot from a real TV
in the PR description. Keep the dock list generic (the big streaming apps) unless the theme is for a
specific TV, and say which TV you tried it on.
