# Vord Roadmap

- [x] M0 - repo scaffold, Manager app skeleton builds/signs, CI publishes releases
- [ ] persistent release keystore (releases currently sign with the ephemeral per-CI-run debug key, no in-place upgrades yet) - do before M1 ships anything users install
- [x] decision: JS core lives in `core/`, a squashed git subtree of Vendicated/Vencord (remote `vencord`) with a Metro adapter replacing the webpack layer
- [ ] M1 - Manager patches a user-supplied Discord APK on-device (inject loader dex as classes4.dex, patch manifest appComponentFactory (not application name: MainApplication is final), re-zip/align/sign all splits, target Discord 353.11)
- [ ] M2 - JS core runtime loaded into Discord's Hermes context, one real Metro module patched
- [ ] stub `PlayIntegrityModule` (RN TurboModule, age assurance) so it rejects cleanly instead of crashing; no forged verdicts (needs M2 hook; check what Discord JS does on rejection first)
- [ ] first plugin: hide passkey / RP ID sign-in options (they cannot work on the renamed package and cert; find the UI module or feature flag with the M2 module dump)
- [ ] M3 - plugin API (patcher/finder utilities), plugin manifest format, local + remote plugin loading, Manager UI for managing plugins and Discord versions
