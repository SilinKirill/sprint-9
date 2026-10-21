# BionicPRO — спринт 9

## Запуск

Нужны Docker с Linux-контейнерами и Docker Compose 2.24 или новее. Команды выполняются из корня репозитория и одинаковы для Windows, Linux и macOS:

```sh
docker compose -f compose.setup.yaml run --rm --build setup
docker compose up -d --build
```

Первая команда создаёт локальные `.env`, ключ шифрования и TLS-сертификат внутри контейнера. Существующие файлы сохраняются. Вторая собирает приложения и автоматически настраивает Keycloak, MinIO, Kafka, Debezium, ClickHouse и Airflow. Успешное завершение служебных контейнеров (`Exited (0)`) ожидаемо; состояние видно через `docker compose ps -a`.

Для Яндекс ID после первой команды укажите `YANDEX_ENABLED=true`, `YANDEX_CLIENT_ID` и `YANDEX_CLIENT_SECRET` в `Task1/.env`; параметры OAuth-приложения описаны в [Task1](Task1/README.md#6-яндекс-id). Без этих настроек доступны локальный вход и LDAP. После изменения настроек повторите `docker compose up -d --build`.

- [Приложение](https://localhost:8443) — разрешите локальный самоподписанный сертификат. `prothetic1` / `prothetic123`, при первом входе настройте OTP.
- [Keycloak](https://localhost:8443/admin/) — `admin` / `admin`.
- [Airflow](http://localhost:8081) — `admin` / `airflow_demo`.

Для получения отчётов за двое предыдущих суток UTC выполните:

```sh
docker compose run --rm --no-deps demo-data
```

Команда привязывает демонстрационные записи к существующему `prothetic1`, запускает DAG и выводит даты отчёта. После завершения подождите до 20 секунд для обновления витрины, затем выберите эти даты в приложении.

Остановка: `docker compose stop`. Повторный запуск — `docker compose up -d`. Данные сохраняются в существующих томах проекта `sprint-9` и каталоге `postgres-keycloak-data`. Не используйте `down -v`, если данные нужны. Повторное применение настроек Keycloak сохраняет пользователей и OTP, но завершает сеансы входа.

## Задания

[Задание 1: инструкция, схема и код](Task1/README.md).

[Задание 2: отчёты, Airflow и ClickHouse](Task2/README.md).

[Задание 3: MinIO, повторное использование отчётов и кеш Nginx](Task3/README.md).

[Задание 4: Debezium, Kafka, KafkaEngine и новая витрина отчётности](Task4/README.md).
