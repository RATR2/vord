# Vord Roadmap

- [x] M0 - repo scaffold, Manager app skeleton builds/signs, CI publishes releases
- [ ] persistent release keystore (releases currently sign with the ephemeral per-CI-run debug key, no in-place upgrades yet) - do before M1 ships anything users install
- [ ] M1 - Manager patches a user-supplied Discord APK on-device (inject loader dex, patch manifest application class, re-zip/align/sign, prompt install)
- [ ] M2 - JS core runtime loaded into Discord's Hermes context, one real Metro module patched
- [ ] M3 - plugin API (patcher/finder utilities), plugin manifest format, local + remote plugin loading, Manager UI for managing plugins and Discord versions
