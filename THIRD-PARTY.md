# Third-party components

Ferret is released under the MIT license (see `LICENSE`). The app bundles the following third-party components; their
license texts travel with them (`META-INF/LICENSE` inside the library jars, `runtime/legal` in the bundled Java).

| Component | Version | License | Source |
|---|---|---|---|
| FlatLaf | 3.7.2 | Apache License 2.0 | https://github.com/JFormDesigner/FlatLaf |
| RSyntaxTextArea | 4.0.1 | BSD 3-Clause | https://github.com/bobbylight/RSyntaxTextArea |
| Java runtime (Eclipse Temurin 21, trimmed with jlink) | 21 | GPLv2 with Classpath Exception | https://adoptium.net |

**Database drivers are not bundled.** Ferret downloads a driver from Maven Central only when you connect to a database
of that type, and verifies its checksum. Every driver has its own license (e.g. the Oracle and MySQL drivers are under
their owners' terms); by downloading it you accept that license.
