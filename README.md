# ChessLAN ♟️

[![Download APK](https://img.shields.io/badge/Download-APK-2EA043?style=for-the-badge&logo=android&logoColor=white)](https://github.com/nikanokh/android-lan-chess/releases/latest)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)

**ChessLAN** — Android-приложение для игры в шахматы по локальной сети (Wi-Fi / точка доступа) без интернета, игры вдвоём на одном устройстве и офлайн-анализа партий с движком Stockfish.

---

## 📥 Скачать APK

1. Перейдите на страницу релизов: **[GitHub Releases (последняя версия)](https://github.com/nikanokh/android-lan-chess/releases/latest)**
   или скачайте файл напрямую: **[ChessLAN-debug.apk](https://github.com/nikanokh/android-lan-chess/releases/latest/download/ChessLAN-debug.apk)** *(появится сразу после публикации первого релиза)*.
2. Откройте скачанный `.apk` на телефоне Android **7.0 (API 24)** или выше.
3. При необходимости разрешите установку приложений из неизвестных источников в настройках браузера или файлового менеджера.

---

## ✨ Возможности

### 🌐 Игра по локальной сети (LAN)
- **Без интернета и серверов:** встроенный хост-сервер на `Ktor WebSockets` внутри приложения.
- **Автопоиск и QR-код:** автоматическое обнаружение комнат по локальной сети (NSD) и мгновенное подключение по QR-коду (`CameraX` + `ZXing`) или IP-адресу.
- **Полный контроль партии:**
  - Предложение ничьей, сдача (с опциональным подтверждением), премувы и таймеры с контролем времени (`Bullet`, `Blitz`, `Rapid`, `Classic`, свой контроль).
  - **Запрос хода назад:** возвращает позицию ровно до вашего хода (1 полуход на ходе соперника, 2 полухода на вашем ходе).
  - **Новая партия без перезапуска лобби:** предложение реванша в той же комнате со сменой цветов.
  - **Контроль выхода из партии:** при выходе из незавершённой партии выводится предупреждение, а выход или явное отключение засчитывается как поражение.
  - Чёткое окно результата партии (**Победа** / **Ничья** / **Поражение**).

### 🧠 Офлайн-анализатор партий (Stockfish)
- **Оценка позиций и дерево вариантов:** встроенный движок Stockfish, лучшие линии (`MultiPV`), стрелки вариантов на доске и классификация ходов (точность в стиле Lichess, график оценки).
- **Импорт партий:**
  - Загрузка партий из **Chess.com** (выбор по месяцам / архивам и быстрый выбор из недавних имён).
  - Импорт PGN / FEN или открытие любой партии из локальной истории ChessLAN.
- **Гибкая шкала оценки (Eval Bar):**
  - Расположение **слева** от доски (вертикально), **сверху** над доской (горизонтально) или полное отключение.
  - Отображение оценки **внутри шкалы**, **отдельным блоком** (под вертикальным баром или справа от горизонтального) либо скрытие цифр.

### 📚 История и персонализация
- **История партий (`Room`):** автоматическое сохранение сыгранных партий с записью PGN, переходом к анализу в одно нажатие и удалением записей с подтверждением.
- **Темы и настройки (`DataStore`):** несколько цветовых тем доски с подобранными цветами активных ходов, наборы фигур, звук, вибрация, координаты, анимации и имя игрока.

---

## 🛠️ Технологический стек

- **Язык и UI:** Kotlin, Jetpack Compose, Material 3
- **Архитектура и DI:** MVVM, Dagger Hilt, Coroutines / Flow
- **Шахматная логика и анализ:** `chesslib`, встроенный Stockfish (WebView / WASM)
- **Сеть:** Ktor CIO (WebSockets Server & Client), Android NSD, CameraX, ZXing
- **Хранение данных:** Room Database, Jetpack DataStore Preferences, Kotlinx Serialization

---

## 🔧 Сборка из исходников

Требования: Android SDK (**API 36**, минимум **API 24**), JDK 17/21 и Gradle **9.3.1**.

```bash
# Сборка Debug APK
gradle assembleDebug

# Запуск unit- и Compose/Robolectric-тестов
gradle testDebugUnitTest
```

Готовый APK появится в `app/build/outputs/apk/debug/app-debug.apk`.
Для сборки Release APK требуются переменные окружения `KEYSTORE_PATH`, `STORE_PASSWORD` и `KEY_PASSWORD` (см. `app/build.gradle.kts`).

---

## 📦 Публикация APK через GitHub Actions

В репозитории настроен workflow [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml):
- При пуше тега вида `v1.0.0` (или ручном запуске через **Actions → Build & Release APK**) автоматически запускаются тесты, собирается `ChessLAN-debug.apk` и создаётся релиз в **GitHub Releases**.
