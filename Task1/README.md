# Задание 1. Повышение безопасности BionicPRO

Подзадачи используют общие frontend, Keycloak и сервер авторизации.

## 1. Архитектурное решение

[architecture.drawio](architecture.drawio) содержит доработанную схему. Сохранены протез, приложение, API, базы, магазин, CRM, ML-инженер и их существующие связи. Пользовательские запросы идут через добавленный `bionicpro-auth`; Keycloak подключает местный каталог LDAP и внешних провайдеров идентификации (IdP). Поток телеметрии от чипа в API сохраняется.

Каждая страна разворачивает свой контур и хранит персональные/медицинские данные, журналы и резервные копии локально. Общими остаются правила входа и роли; каталог и внешний провайдер выбираются для страны обслуживания. В текущей конфигурации запущен один контур.

## 2. PKCE

Используется Authorization Code с **PKCE S256**:

1. `bionicpro-auth` создаёт случайный `code_verifier`, сохраняет его на сервере и отправляет Keycloak `code_challenge = BASE64URL(SHA256(verifier))`.
2. После входа Keycloak возвращает одноразовый код. BFF проверяет `state` и обменивает код вместе с исходным `verifier` на токены.
3. Keycloak сверяет challenge. Перехваченного кода без verifier недостаточно. BFF дополнительно проверяет подпись токенов, issuer, audience и срок действия. В ID-токене BFF также проверяет `nonce` — случайное одноразовое значение, которое связывает токен с текущей попыткой входа.

Код: [bionicpro-auth](bionicpro-auth). Настройка клиента: `pkce.code.challenge.method: S256` в [экспорте realm](keycloak/keycloak-results-export.json). Фронтенд начинает вход через `/auth/login`, а токены получает сервер. [RFC 7636](https://www.rfc-editor.org/rfc/rfc7636).

## 3. Сервер `bionicpro-auth` и сессии

Сервер написан на **Java 21 / Spring Boot + Spring Security**. Spring Security проверяет JWT и защищает эндпоинты через сессионную cookie. Access token хранится в памяти, refresh token — в памяти в зашифрованном виде (AES-GCM, ключ из локального файла). Браузеру выдаётся случайный session id в cookie `HttpOnly; Secure; SameSite=Lax`.

Access token действует 120 секунд, сессия — 30 минут. BFF обновляет токены через refresh token и меняет session id при каждом защищённом запросе, даже если access token ещё действует. Старый session id отклоняется. POST-запросы защищены проверкой Origin и CSRF. Выход завершает сессию BFF и Keycloak.

[Фронтенд](frontend) передаёт cookie и последовательно выполняет защищённые запросы. `/api/me` служит защищённым ресурсом для проверки. Один экземпляр BFF хранит сессии в памяти: при перезапуске нужен повторный вход.

## 4. LDAP

[ldap/config.ldif](ldap/config.ldif) содержит учебных пользователей и группы. Keycloak обращается к OpenLDAP по LDAPS и отображает группы в роли: `john.doe` и `alex.johnson` → `prothetic_user`, `jane.smith` → `user`.

Пароль проверяется в LDAP. Учётная запись импортируется в местный Keycloak для хранения OTP. Федерация и mapper находятся в том же [экспорте realm](keycloak/keycloak-results-export.json).

## 5. MFA

OTP обязателен в обычном входе и после входа через внешний провайдер. При первом входе пользователь сканирует QR в Google Authenticator или FreeOTP и вводит текущий код. При последующих входах нужен код из сохранённой записи.

Настройки: flows `bionicpro-browser`, `bionicpro-broker-mfa` и действие `CONFIGURE_TOTP` в [экспорте realm](keycloak/keycloak-results-export.json). Пароля без OTP недостаточно.

## 6. Яндекс ID

[keycloak/yandex-provider](keycloak/yandex-provider) — небольшой Java-адаптер OAuth 2.0 для Identity Brokering: в используемом Keycloak 21.1.2 нет встроенного Яндекс ID. Для входа брокер запрашивает `/info`, но импортирует только идентификатор.

После входа BionicPRO отдельно спрашивает согласие. Только после согласия BFF получает профиль Яндекса, сверяет внешний id с подписанным claim и сохраняет выбранные поля в SQLite вместе с согласием. При отказе приложение профиль не загружает и не сохраняет. Токен Яндекса остаётся на сервере. Профили хранятся в Docker volume `auth-data`.

Для входа через Яндекс зарегистрировано OAuth-приложение **BionicPRO** со следующими настройками:

- Redirect URI: `https://localhost:8443/realms/reports-realm/broker/yandex/endpoint`.
- Доступы: `login:info` и `login:email`.
- Вход включён параметром `YANDEX_ENABLED=true`; `YANDEX_CLIENT_ID` и `YANDEX_CLIENT_SECRET` сохранены в локальном `Task1/.env`. Файл исключён из Git.

## Запуск и проверка

Запуск всех заданий описан в [корневом README](../README.md#запуск). Подготовка секретов и сертификата выполняется контейнером `setup`, настройки Keycloak автоматически применяет `keycloak-setup`. Пользователи и OTP сохраняются, старые сеансы входа завершаются. Параметры Яндекс ID задаются в `Task1/.env` перед запуском (пункт 6).

Открыть [BionicPRO](https://localhost:8443); при первом открытии разрешить переход на локальный самоподписанный сертификат. [Админка Keycloak](https://localhost:8443/admin/): `admin` / `admin` (учебные данные).

- Локальный пользователь: `prothetic1` / `prothetic123`.
- LDAP-пользователь: `john.doe` / `password`.

Войти с OTP, нажать «Обновить сведения», подождать 2 минуты 10 секунд и нажать ещё раз: сведения должны обновиться без повторного входа. Для Яндекса разрешить сохранение профиля, выйти и войти снова: сообщение о сохранённом профиле остаётся, повторного запроса согласия нет. Без входа `/api/me` возвращает 401. Данные для кнопки отчёта подготавливаются по [инструкции Task2](../Task2/README.md#подготовка-данных).

## Основные файлы

- `docker-compose.yaml`, Dockerfile и `nginx.conf` — сборка, запуск и HTTPS.
- `bionicpro-auth/` — сервер авторизации на Java и Spring Security.
- `keycloak/keycloak-results-export.json` — конфигурация Keycloak; `keycloak/provision.py` применяет её к существующей БД, `keycloak/entrypoint.sh` подключает сертификат LDAPS.
- `../setup/`, `.env.example` — подготовка локальных настроек; секреты и данные не входят в Git.

Данные существующего PostgreSQL остаются в `../postgres-keycloak-data`; имя Compose-проекта `sprint-9` сохраняет уже созданные volumes.
