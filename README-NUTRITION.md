# Ravil Nutrition Bridge — Руководство пользователя

## Описание

**Ravil Nutrition Bridge** — отдельное Android-приложение в этом репозитории, которое получает записи о питании с сервера `health-connect.ravil.space` и записывает их в Android Health Connect. Приложение работает независимо от основного HC Webhook Exporter и **не требует его удаления**.

- `applicationId`: `com.ravil.healthconnect.nutrition`
- Модуль Gradle: `:nutrition`
- Требует только разрешение `WRITE_NUTRITION` — никаких посторонних READ-разрешений

---

## Архитектура

```
Сервер (GET /nutrition/queue)
        ↓
NutritionApiClient (OkHttp, HTTPS, X-Nutrition-Token в заголовке)
        ↓
RecordValidator → validate()
        ↓
InsertBookmarks → проверка версии (upsert-идемпотентность)
        ↓
HealthConnectWriter.insertRecord()  — metadata.clientRecordId + version
        ↓ success
InsertBookmarks.withApplied()  — сохранение до ack
        ↓
NutritionApiClient.ackRecord() — POST /nutrition/ack
```

### Файлы ключевые

| Файл | Назначение |
|------|-----------|
| `sync/SyncPlan.kt` | Чистая функция `planSync()` — определяет действие для каждой записи |
| `sync/NutritionSyncManager.kt` | Оркестрирует синхронизацию, сохраняет закладки |
| `sync/NutritionSyncWorker.kt` | WorkManager CoroutineWorker |
| `crypto/TokenStore.kt` | EncryptedSharedPreferences (Android Keystore) |
| `health/HealthConnectWriter.kt` | Запись NutritionRecord в Health Connect |
| `validation/RecordValidator.kt` | Валидация полей и времени (ISO 8601 с часовым поясом) |
| `bookmarks/InsertBookmarks.kt` | Bookmarks: clientRecordId → appliedVersion (без декремента) |
| `network/NutritionApiClient.kt` | GET /nutrition/queue и POST /nutrition/ack |

---

## Ручная установка и настройка

### 1. Установить APK

```
adb install -r nutrition/build/outputs/apk/debug/nutrition-debug.apk
```

### 2. Запустить приложение "Ravil Nutrition Bridge"

Приложение устанавливается рядом с основным HC Webhook — они не конфликтуют (разные `applicationId`).

### 3. В интерфейсе приложения

1. **Server URL** — введите `https://health-connect.ravil.space` (только HTTPS)
2. **Nutrition Token** — вставьте токен `X-Nutrition-Token` (поле замаскировано, не отображается)
3. Нажмите **"Settings saved"** для сохранения
4. При необходимости установите **интервал синхронизации** в минутах (минимум 15)
5. Включите переключатель **"Enable Nutrition Sync"** для фоновой синхронизации
6. Нажмите **"Grant WRITE_NUTRITION"**, если разрешение ещё не выдано
7. Нажмите **"Pull Nutrition"** для ручного запроса

### 4. Счётчики

- **Pending** — количество записей в последнем ответе от сервера, ожидающих применения
- **Applied** — суммарное количество успешно записанных в Health Connect
- **Errors** — ошибки валидации или записи в последней синхронизации

---

## Протокол сервера

### GET `/nutrition/queue`
- Заголовок: `X-Nutrition-Token: <token>`
- Ответ `200 OK`:
```json
{
  "schema_version": 1,
  "records": [
    {
      "client_record_id": "string",
      "client_record_version": 1,
      "name": "Завтрак",
      "start_time": "2024-06-01T08:00:00+03:00",
      "end_time": "2024-06-01T08:30:00+03:00",
      "energy_kcal": 450.0,
      "protein_g": 25.0,
      "carbohydrate_g": 55.0,
      "fat_g": 14.0,
      "description": "Овсяная каша с ягодами (AI estimate)"
    }
  ]
}
```

**Обязательные поля:** `client_record_id`, `client_record_version`, `name` (непустое), `start_time`, `end_time` (ISO 8601 со смещением), `energy_kcal` (0–5000), `protein_g` (0–500), `carbohydrate_g` (0–1000), `fat_g` (0–500).

**Известные опциональные поля** (поддерживаются приложением, но MVP-сервер их не отправляет):
| Поле | HC-поле | Конвертация |
|------|---------|-------------|
| `fiber_g` | `dietaryFiber` | г → г (без конвертации) |
| `sugar_g` | `sugar` | г → г |
| `sodium_mg` | `sodium` | мг ÷ 1000 → г |

> Если `description` присутствует, его содержимое добавляется к имени записи в HC: `"name [description]"`. Это единственный текстовый аннотирующий атрибут, доступный в `NutritionRecord`.

> Лимиты: максимум 50 записей за ответ; размер тела ≤ 512 КБ.

### POST `/nutrition/ack`
- Заголовок: `X-Nutrition-Token: <token>`
- Тело:
```json
{
  "client_record_id": "string",
  "client_record_version": 1
}
```
- Ответ: любой `2xx` = успех

**Важно:** ACK отправляется только **после успешной записи** в Health Connect. Если ACK не прошёл — следующий запуск повторит ACK (благодаря bookmarks). Отмена записи на стороне сервера после доставки не гарантирует удаление данных из Health Connect (платформа может сохранить запись до следующей синхронизации устройства).

---

## Безопасность

### Токен
- Хранится в `EncryptedSharedPreferences` (Android Keystore AES-256-GCM)
- Передаётся только в HTTP-заголовке `X-Nutrition-Token`
- **Никогда** не попадает в URL, query-параметры или логи
- Файл шифрованных настроек исключён из резервного копирования (см. `backup_rules.xml`)

### Сеть
- Все редиректы отключены (`followRedirects=false`, `followSslRedirects=false`) — токен не утекает на сторонние серверы
- URL сервера валидируется на каноничность: только HTTPS, без userinfo, query, fragment
- Размер ответа ограничен 512 КБ (по байтам); записей — не более 50

### Закладки и смена сервера
- Закладки (`client_record_id → version`) привязаны к fingerprint = SHA-256(url ‖ token)
- При смене URL **или** ротации токена закладки автоматически сбрасываются — AckOnly не переносится на другой сервер/аккаунт
- Запись закладки на диск выполняется **синхронно** (`commit()`) до отправки ACK: сбой между commit и ACK ведёт к повторному ACK, а не к повторной записи

### Конкурентность
- Глобальный `Mutex` в `NutritionSyncManager.syncMutex` гарантирует, что одновременно работает не более одного цикла синхронизации (фоновый воркер и ручной запуск не пересекаются)

---

## Сборка

```bash
# Из корня репозитория
export JAVA_HOME=~/jdk/jdk-17.0.20.1+1
export ANDROID_HOME=~/android-sdk

./gradlew :nutrition:assembleDebug
./gradlew :nutrition:test
./gradlew :nutrition:lint
```

---

## Известные ограничения

1. **Фоновая запись в Health Connect** — на Android 14+ фоновые операции записи могут быть ограничены платформой. Ручная кнопка "Pull Nutrition" работает всегда при открытом приложении.
2. **Health Connect необходим** — приложение требует установленного Health Connect (Android 13+ встроен, Android 9–12 требует отдельной установки).
3. **Тестирование без устройства** — полное end-to-end тестирование не проводилось (нет реального устройства). Покрыты JVM unit-тесты: валидация, версионирование, парсинг, порядок ACK.
4. **Интервал WorkManager** — платформа гарантирует запуск не чаще одного раза в 15 минут; реальный интервал может быть больше при включённых ограничениях батареи.

---

## Лицензия и происхождение кода

Этот модуль (**`:nutrition`**) написан с нуля как часть репозитория `health-connect-nutrition-android`.

Существующий модуль **`:app`** (HC Webhook Exporter) не изменён. Его лицензия и авторские права сохранены без изменений — см. `LICENSE` и `LICENSE.ADDENDUM` в корне репозитория.

Закреплённый тег источника: **v1.9.22** (commit `a61de8f`).

Приложение **не подлежит коммерческому распространению** без соблюдения условий лицензии исходного репозитория.
