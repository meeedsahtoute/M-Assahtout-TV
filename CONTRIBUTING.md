# Contributing to M. ASSAHTOUT TV

Contributions, bug reports, and UI/playback improvements are welcome. Please keep pull requests focused and tested on a real device, emulator, or desktop build before submitting.

## Building

See [README.md](README.md#building-from-source) for Android TV (`cargo-ndk` + Gradle) and Desktop (`cargo build --release`) build instructions.

## Guidelines

- Keep each pull request focused on a single change.
- Test D-Pad remote navigation carefully for any Android TV UI edits to ensure focus never gets trapped.
- Preserve existing `com.streamtube.tv` package names and JNI bindings so native calls to `libstreamtube_core.so` remain intact.
- Run `cargo fmt` for Rust edits and `gradle assembleRelease` / `compileReleaseKotlin` for Android edits.
- Keep `Cargo.lock` up to date if Cargo dependencies change.

## License

By contributing, you agree that your contribution is licensed under the project's [MIT License](LICENSE).
