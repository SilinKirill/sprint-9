# Задание 4. Повышение оперативности и стабильности работы CRM

Данные CRM доставляются в ClickHouse через **Debezium → Kafka → KafkaEngine**. Массовая выгрузка CRM из Airflow заменена чтением изменений из WAL.

## 1. CDC и Kafka

[docker-compose.yaml](docker-compose.yaml) добавляет Kafka и Kafka Connect с фиксированными версиями и постоянным хранением данных и смещений. Для PostgreSQL включён logical WAL; [sources/cdc.sql](sources/cdc.sql) создаёт отдельную роль и publication. Настройки применяются и к существующей БД без удаления данных.

[debezium/crm-connector.json](debezium/crm-connector.json) настраивает initial snapshot таблиц `crm.clients` и `crm.prostheses`, затем доставку insert/update/delete. События направляются в один топик `bionicpro.crm_events` с одной партицией, чтобы сохранить порядок. Пароль подставляется из локального файла, исключённого из Git.

## 2. KafkaEngine и новая витрина

[clickhouse/cdc.sql](clickhouse/cdc.sql) создаёт KafkaEngine и Materialized View для сохранения событий CRM. Состояние определяется позицией WAL и порядком события в транзакции; повторная доставка не удваивает данные и не возвращает старое состояние, удаления исключают записи из отчётов.

Обновляемая Materialized View `refresh_daily_reports` каждые 10 секунд объединяет CRM с телеметрией и атомарно заменяет `daily_reports_cdc`. Изменение имени, модели или владельца учитывается без новых измерений. Служебный маркер CDC ограничивает состояние завершёнными изменениями; при отставании более 60 секунд выдача закрывается.

[Airflow DAG](../Task2/airflow/dags/report_etl.py) сохраняет расписание 00:00 UTC (03:00 МСК), загружает только измерения за полные сутки и последним действием публикует их поколение. Объединение и расчёт показателей выполняются в ClickHouse. Повторная обработка суток не удваивает измерения.

## 3. API, сохранённые отчёты и кеш

[ReportRepository](../Task2/reports-api/src/main/java/com/bionicpro/reports/repository/ReportRepository.java) читает строки и готовность периода из новой витрины. Доступ ограничен проверенным `sub`, открыты только подготовленные сутки.

Версия вычисляется один раз на сутки из их содержимого и поколения телеметрии. [ReportController](../Task2/reports-api/src/main/java/com/bionicpro/reports/controller/ReportController.java) включает её и признак CDC в хеш ключа MinIO. Изменение данных даёт новый URL и `MISS → HIT` в Nginx; старая ссылка получает `409`. Повторная проверка версии защищает от обновления витрины во время формирования файла. Формат пути `v1` и защита скачивания Task3 сохранены.

## Запуск и проверка

Нужны Docker Desktop с Linux-контейнерами и настроенные и запущенные Task1/Task3. Команды PowerShell выполняются **из корня репозитория**:

```powershell
powershell -ExecutionPolicy Bypass -File .\Task4\setup.ps1
$dc = @('--env-file', 'Task1/.env', '--env-file', 'Task3/.env', '--env-file', 'Task4/.env', '-f', 'Task1/docker-compose.yaml', '-f', 'Task2/docker-compose.yaml', '-f', 'Task3/docker-compose.yaml', '-f', 'Task4/docker-compose.yaml')
docker compose @dc up -d --build --no-deps reports-api airflow-scheduler airflow-webserver
docker compose @dc exec -T airflow-scheduler python /opt/airflow/task4/migrate_periods.py
docker compose @dc exec -T kafka-connect curl -fsS http://localhost:8083/connectors/bionicpro-crm/status
```

Настройка повторяемая: существующие секреты, replication slot, смещения и volumes сохраняются. Скрипт миграции загружает телеметрию только для ранее подготовленных суток, которых ещё нет в новой витрине. Для новых дат используется DAG по [инструкции Task2](../Task2/README.md#подготовка-данных) с `$dc` выше.

Приложение: [https://localhost:8443](https://localhost:8443). Airflow: [http://localhost:8081](http://localhost:8081).

В UI дважды скачайте отчёт за готовые сутки: ссылка одинакова, повторный запрос — `HIT`. После изменения CRM дождитесь обновления витрины без запуска Airflow и получите новую ссылку, `MISS`, затем `HIT`; старая ссылка — `409`. Без входа ожидается `401`, чужой файл — `404`, неготовые сутки — `409`.

## Основные файлы

- `docker-compose.yaml`, `setup.ps1` — развёртывание и повторяемое применение настроек.
- `debezium/`, `sources/cdc.sql` — connector и настройки PostgreSQL.
- `clickhouse/cdc.sql` — KafkaEngine, приём событий и Materialized View витрины.
- `../Task2/airflow/`, `airflow/migrate_periods.py` — загрузка телеметрии и перенос готовых периодов.
- `../Task2/reports-api/` — чтение новой витрины и обновление сохранённых отчётов.
