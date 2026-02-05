# Contributing

Thank you for your interest in contributing to Vouch!

## Getting Started

### Prerequisites

- **Java 21+** (JDK, not JRE)
- **Git**
- **Gradle** (wrapper included)

### Clone & Build

```bash
git clone https://github.com/nozzdev/vouch.git
cd vouch
./gradlew build
```

### Project Structure

```
common/     # Shared code (Architectury) — most changes go here
fabric/     # Fabric-specific entrypoint and implementations
neoforge/   # NeoForge-specific entrypoint and implementations
docs/       # VitePress documentation (this site)
```

### Running a Test Server

```bash
# Fabric
./gradlew :fabric:runServer

# NeoForge  
./gradlew :neoforge:runServer
```

---

## How to Contribute

### Reporting Bugs

1. Check [existing issues](https://github.com/nozzdev/vouch/issues) first
2. Include:
   - Minecraft version and mod loader
   - Vouch version
   - Steps to reproduce
   - Expected vs actual behavior
   - Relevant logs (`logs/latest.log`)
   - `vouch.toml` (redact credentials)

### Requesting Features

Open a [GitHub Issue](https://github.com/nozzdev/vouch/issues) with:
- Clear description of the feature
- Use case / motivation
- Proposed behavior

### Submitting Code

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make your changes in the `common/` module when possible
4. Test on both Fabric and NeoForge
5. Submit a Pull Request

### Code Guidelines

- **Java 21** features are welcome (records, sealed classes, pattern matching)
- Follow existing code style
- Add comments for complex logic
- Keep async operations on dedicated thread pools (never block the main thread)
- Test with multiple database backends if touching DB code

---

## Translating

Adding a new language is one of the easiest ways to contribute:

1. Copy `common/src/main/resources/assets/vouch/lang/en_us.json`
2. Rename to your locale code (e.g., `fr_fr.json`, `de_de.json`, `pt_br.json`)
3. Translate all values (keep keys the same)
4. Submit a Pull Request

### Translation Guidelines

- Keep messages concise (chat space is limited)
- Preserve placeholders: `{player}`, `{time}`, `{min}`, `{max}`, etc.
- Preserve color codes: `&a`, `&c`, `&6`, etc.
- Test in-game if possible

---

## Documentation

The documentation site uses [VitePress](https://vitepress.dev/) and lives in the `docs/` directory.

### Running Locally

```bash
bun run docs:dev
# or
npx vitepress dev docs
```

### Building

```bash
bun run docs:build
```

---

## Sponsor / Support

If you enjoy Vouch and want to support its development, consider buying me a coffee:

<a href="https://ko-fi.com/nozzdev" target="_blank">
  <img src="https://storage.ko-fi.com/cdn/kofi2.png?v=6" alt="Support on Ko-fi" height="40">
</a>

Your support helps cover:
- Server costs for testing infrastructure
- Development time for new features
- Maintaining compatibility with future Minecraft versions

Every contribution, no matter how small, is deeply appreciated. Thank you! 💛

---

## License

Vouch is released under the [MIT License](https://opensource.org/licenses/MIT). By contributing, you agree that your contributions will be licensed under the same license.
