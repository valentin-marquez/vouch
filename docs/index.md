---
layout: home

hero:
  name: Vouch
  text: Verify. Authenticate. Play.
  tagline: Secure server-side authentication solution featuring Argon2id hashing, 2FA TOTP, and session persistence.
  image:
    src: /icon.png
    alt: Vouch
  actions:
    - theme: brand
      text: Get Started
      link: /1.21.1/
    - theme: alt
      text: View on GitHub
      link: https://github.com/nozzdev/vouch
    - theme: alt
      text: ☕ Support on Ko-fi
      link: https://ko-fi.com/nozzdev

features:
  - icon: 🔐
    title: Argon2id Hashing
    details: Secure password hashing using Argon2id via Bouncy Castle. Configurable memory cost, iterations, and parallelism.
  - icon: 📱
    title: TOTP Two-Factor Auth
    details: RFC 6238 compliant 2FA with QR code rendered as in-game maps. Compatible with Google Authenticator, Authy, and Aegis.
  - icon: 🗄️
    title: Multi-Database Support
    details: H2, SQLite, MySQL, and PostgreSQL with HikariCP connection pooling. Environment variable support for credentials.
  - icon: ⚡
    title: Fully Async
    details: Zero TPS impact — all cryptographic operations and database queries run on dedicated thread pools.
  - icon: 🎮
    title: Fabric & NeoForge
    details: Built with Architectury API for seamless multi-platform support. One codebase, two loaders.
  - icon: 🛡️
    title: Pre-Auth Jail
    details: Complete player isolation with blindness, invisibility, position freeze, and tab list hiding until authenticated.
---

