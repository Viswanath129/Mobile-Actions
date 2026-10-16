# Tool status

## Registered schema names
- `open_wifi_settings`
- `turn_on_flashlight`
- `turn_off_flashlight`
- `show_location_on_map`
- `create_contact`
- `create_calendar_event`
- `send_email`

## Implemented handlers
- `open_wifi_settings`: launches Android Wi-Fi settings.
- `show_location_on_map`: opens a map search with a `geo:` intent.
- `create_contact`: opens the Android contact form; user must review/save.
- `create_calendar_event`: opens the calendar form; user must review/save.
- `send_email`: opens an email composer; never sends silently.

## Registered but not implemented
- `turn_on_flashlight` / `turn_off_flashlight`: tool schema exists, but hardware toggling intentionally returns a message instead of acting.

## Not implemented
- Selfie/photo capture, Spotify playback, brightness control, SMS, alarms, Bluetooth, weather and all other custom tools.
- Your full 108-tool schema has not been included because it was not supplied to this build step.
- The current starter refuses sensitive actions rather than executing without a confirmation UI. Add a real confirmation state flow before enabling these.
