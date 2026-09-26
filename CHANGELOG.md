# Changelog

All notable changes to Streamlord are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-09-26

The first forging.

### Added

- `editors/vscode`: the Streamlord VS Code extension (diagnostics, completions, hover,
  highlighting, Stream Inspector) and `catalog/datastar-1.0.4.json`, the shared source of
  truth verified by `CatalogTest`.

- `streamlord-core`: Datastar 1.0.4 protocol model (`PatchElements`, `PatchSignals`,
  `ExecuteScript`), `SseEncoder` verified against the official SDK golden files, non-SSE
  `DatastarResponse` types, a dependency-free strict JSON parser and writer, the
  `DatastarStream` driving port and the `SseSink`, `SignalsCodec` and `IncomingRequest`
  driven ports, and the `Streamlord` facade with bounded signal reading.
- `streamlord-html`: kotlinx.html DSL for every Datastar attribute, modifier, action and
  expression helper; `patchElements { }` for streams, events and responses.
- `streamlord-ktor`: `respondDatastar`, `respondElements`/`respondSignals`/`respondScript`,
  `readSignals`, the `StreamlordPlugin`, and an in-process run of the official SDK
  conformance server.
- `streamlord-spring`: `datastarStream` over `StreamingResponseBody`, `readSignals` over
  `HttpServletRequest`, `ResponseEntity` helpers, `Flow<DatastarEvent>.asServerSentEvents()`
  for WebFlux, and `ElementPatchModeConverter`.
- `streamlord-json-kotlinx`, `streamlord-json-jackson` (Jackson 3, Spring Boot 4) and
  `streamlord-json-jackson2` (Jackson 2, Spring Boot 3): codec adapters for typed signals.
- `streamlord-html`: `payload` and `responseOverrides` fetch options, `dataOnFetch` with
  `FetchEventType` constants, and `DatastarAttributes.prefix` for the aliased `data-star-*` bundle.
- `streamlord-html-pro`: opt-in helpers for the attribute names and actions of Datastar Pro,
  containing no Pro code. The Spring adapter is compiled against Spring Framework 6.2 and
  tested against 6.2 and 7.0.
