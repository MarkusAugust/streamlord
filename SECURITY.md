# Security

Streamlord reads what the browser sends and writes what the browser runs, so a flaw in it is a
flaw in every application built on it. Reports are welcome, and taken seriously.

## Reporting a vulnerability

Report it privately, through
[GitHub's private vulnerability reporting](https://github.com/MarkusAugust/streamlord/security/advisories/new).
Please do not open a public issue or pull request for it.

A useful report says which module and version it concerns, what an attacker controls, and what
they gain. A request or a test that shows it is worth more than any description.

You will hear back within a week. When the flaw is confirmed, it is fixed in a release, and an
advisory with a CVE is published once that release is out. You are credited in it, unless you
would rather not be.

## Supported versions

Streamlord is before 1.0, and fixes go into the latest release only.

| Part                | Supported                |
| ------------------- | ------------------------ |
| SDK (Maven Central) | The latest `0.x` release |
| VS Code extension   | The latest release       |
| IntelliJ plugin     | The latest release       |

## What is in scope

- The SDK modules published to Maven Central: parsing of signals and requests from the browser,
  encoding of Server-Sent Events, and the Ktor and Spring adapters.
- The VS Code extension and the IntelliJ plugin.
- The streamlord-live service behind the documentation site.

The demo application is in scope only where it shows the SDK doing something unsafe.
