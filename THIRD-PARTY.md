# Сторонні компоненти

Ferret поширюється за ліцензією MIT (див. `LICENSE`). Усередині програми — такі сторонні компоненти; тексти їхніх
ліцензій лежать у них самих (`META-INF/LICENSE` у файлах бібліотек, `runtime/legal` у вкладеній Java).

| Компонент | Версія | Ліцензія | Звідки |
|---|---|---|---|
| FlatLaf | 3.7.2 | Apache License 2.0 | https://github.com/JFormDesigner/FlatLaf |
| RSyntaxTextArea | 4.0.1 | BSD 3-Clause | https://github.com/bobbylight/RSyntaxTextArea |
| Java (Eclipse Temurin 21, урізана jlink) | 21 | GPLv2 з Classpath Exception | https://adoptium.net |

**Драйвери баз даних у програму не вкладені.** Ferret завантажує драйвер з Maven Central лише тоді, коли ти
підключаєшся до бази цього типу, і звіряє контрольну суму. Кожен драйвер має власну ліцензію (наприклад, драйвери
Oracle і MySQL — на умовах їхніх власників); погоджуючись на завантаження, ти приймаєш її.
