# Vord Roadmap

- [x] M0 - repo scaffold, Manager app skeleton builds/signs, CI publishes releases
- [ ] persistent release keystore (releases currently sign with the ephemeral per-CI-run debug key, no in-place upgrades yet) - do before M1 ships anything users install
- [x] decision: JS core is a Vencord fork (`vord-core`, upstream Vendicated/Vencord) with a Metro adapter replacing the webpack layer
- [ ] M1 - Manager patches a user-supplied Discord APK on-device (inject loader dex as classes4.dex, patch manifest appComponentFactory (not application name: MainApplication is final), re-zip/align/sign all splits, target Discord 353.11)
- [ ] M2 - JS core runtime loaded into Discord's Hermes context, one real Metro module patched
- [ ] M3 - plugin API (patcher/finder utilities), plugin manifest format, local + remote plugin loading, Manager UI for managing plugins and Discord versions
