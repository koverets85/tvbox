# TVHub (tvbox)

Android TV box app: catalog of apps from official sources + self-update.

- `catalog.json` — app list, read by the app from `main`. Editing it does not trigger a build.
- Any push to `main` builds a signed APK and publishes a GitHub Release (`tvbox.apk`, `version.json`).
- Latest APK: https://github.com/koverets85/tvbox/releases/latest/download/tvbox.apk
