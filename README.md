# Vord

A Discord Android mod loader with a JavaScript plugin API, in the spirit of Vencord.

Vord ships as a standalone "Manager" app. It never redistributes a modified copy of Discord
itself — instead, on your device, it downloads the official Discord APK and patches it locally
before installing it. Plugins are JavaScript, hooking into Discord's own React Native/Metro module
registry at runtime, the same conceptual trick Vencord uses on webpack modules for desktop.

## Status

Early scaffold. See [ROADMAP.md](ROADMAP.md) for what's built vs. planned.

## Building

```
./gradlew assembleRelease
```

Requires the Android SDK (compileSdk 37) and JDK 17. CI builds and publishes releases
automatically; see `.github/workflows/build.yml`.

## License

GPL-3.0-only, see [LICENSE](LICENSE).
