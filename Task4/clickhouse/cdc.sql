CREATE DATABASE IF NOT EXISTS reports;

CREATE TABLE IF NOT EXISTS reports.crm_events (
    entity LowCardinality(String),
    id String,
    data String,
    deleted UInt8,
    snapshot UInt8,
    kafka_offset UInt64,
    source_lsn UInt64,
    source_order UInt64
) ENGINE = MergeTree ORDER BY (entity, id, kafka_offset);

ALTER TABLE reports.crm_events ADD COLUMN IF NOT EXISTS source_lsn UInt64;
ALTER TABLE reports.crm_events ADD COLUMN IF NOT EXISTS source_order UInt64;

CREATE TABLE IF NOT EXISTS reports.crm_kafka (message String)
ENGINE = Kafka SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list = 'bionicpro.crm_events',
    kafka_group_name = 'bionicpro_clickhouse_cdc',
    kafka_format = 'JSONAsString',
    kafka_num_consumers = 1,
    kafka_flush_interval_ms = 1000;

CREATE MATERIALIZED VIEW IF NOT EXISTS reports.crm_capture TO reports.crm_events AS
WITH JSONExtractString(message, 'op') AS op,
     JSONExtractString(message, 'source', 'table') AS entity,
     if(op = 'd', JSONExtractRaw(message, 'before'), JSONExtractRaw(message, 'after')) AS data
SELECT entity,
       multiIf(entity = 'clients', JSONExtractString(data, 'client_id'),
               entity = 'prostheses', JSONExtractString(data, 'prosthesis_id'),
               toString(JSONExtractInt(data, 'id'))) AS id,
       data, toUInt8(op = 'd') AS deleted, toUInt8(op = 'r') AS snapshot,
       _offset AS kafka_offset,
       JSONExtractUInt(message, 'source', 'lsn') AS source_lsn,
       greatest(JSONExtractUInt(message, 'transaction', 'total_order'),
           toUInt64OrZero(JSONExtractString(message, 'transaction', 'total_order'))) AS source_order
FROM reports.crm_kafka WHERE op IN ('r', 'c', 'u', 'd');

CREATE TABLE IF NOT EXISTS reports.telemetry_daily (
    report_date Date, batch_id UUID, event_id String, prosthesis_id String,
    recorded_at DateTime64(6, 'UTC'), response_ms Float64, battery_pct Float64, successful UInt8
) ENGINE = MergeTree PARTITION BY toYYYYMM(report_date)
ORDER BY (report_date, batch_id, prosthesis_id, event_id);

CREATE TABLE IF NOT EXISTS reports.telemetry_periods (
    report_date Date, batch_id UUID, version UInt64
) ENGINE = ReplacingMergeTree(version) ORDER BY report_date;

-- Rows and readiness/version markers are replaced atomically by the refresh.
CREATE TABLE IF NOT EXISTS reports.daily_reports_cdc (
    user_id String, report_date Date, batch_id String, client_id String,
    display_name String, prosthesis_id String, model String,
    samples UInt64, successful_movements UInt64,
    avg_response_ms Nullable(Float64), min_battery_pct Nullable(Float64),
    is_period UInt8, cdc_checked_at DateTime64(6, 'UTC')
) ENGINE = MergeTree ORDER BY (user_id, report_date, prosthesis_id);

-- Recreate only the view definition; the separate target table keeps its data.
DROP VIEW IF EXISTS reports.refresh_daily_reports SYNC;
CREATE MATERIALIZED VIEW reports.refresh_daily_reports
REFRESH EVERY 10 SECOND TO reports.daily_reports_cdc AS
WITH
    -- One topic/partition preserves the connector order. A live heartbeat follows
    -- snapshot completion and all preceding committed CRM events.
    barrier AS (
        SELECT argMax(kafka_offset, tuple(source_lsn, source_order, kafka_offset)) AS watermark,
               parseDateTime64BestEffortOrNull(argMax(JSONExtractString(data, 'checked_at'),
                   tuple(source_lsn, source_order, kafka_offset)), 6, 'UTC') AS checked_at
        FROM reports.crm_events WHERE entity = 'cdc_heartbeat' AND snapshot = 0
    ),
    state AS (
        SELECT entity, id, argMax(tuple(data, deleted), tuple(source_lsn, source_order, kafka_offset)) AS latest
        FROM reports.crm_events
        WHERE kafka_offset <= (SELECT watermark FROM barrier)
        GROUP BY entity, id
        HAVING latest.2 = 0
    ),
    devices AS (
        SELECT JSONExtractString(c.latest.1, 'user_id') AS user_id,
               p.id AS prosthesis_id, JSONExtractString(c.latest.1, 'client_id') AS client_id,
               JSONExtractString(c.latest.1, 'display_name') AS display_name,
               JSONExtractString(p.latest.1, 'model') AS model,
               parseDateTime64BestEffort(JSONExtractString(p.latest.1, 'assigned_at'), 6, 'UTC') AS assigned_at,
               toUInt8(0) AS is_period
        FROM state p INNER JOIN state c ON JSONExtractString(p.latest.1, 'client_id') = c.id
        WHERE p.entity = 'prostheses' AND c.entity = 'clients'
        UNION ALL
        SELECT '', '', '', '', '', toDateTime64(0, 6, 'UTC'), toUInt8(1)
    ),
    periods AS (
        SELECT report_date, argMax(batch_id, version) AS telemetry_batch
        FROM reports.telemetry_periods WHERE report_date < toDate(now('UTC')) GROUP BY report_date
    ),
    metrics AS (
        SELECT d.user_id AS user_id, t.report_date AS report_date, d.client_id AS client_id,
               d.display_name AS display_name, d.prosthesis_id AS prosthesis_id, d.model AS model,
               toUInt64(countIf(m.event_id != '' AND m.recorded_at >= d.assigned_at)) AS samples,
               toUInt64(countIf(m.event_id != '' AND m.recorded_at >= d.assigned_at AND m.successful = 1)) AS successful_movements,
               avgOrNullIf(m.response_ms, m.event_id != '' AND m.recorded_at >= d.assigned_at) AS avg_response_ms,
               minOrNullIf(m.battery_pct, m.event_id != '' AND m.recorded_at >= d.assigned_at) AS min_battery_pct,
               d.is_period AS is_period, t.telemetry_batch AS telemetry_batch
        FROM periods t CROSS JOIN devices d
        LEFT JOIN reports.telemetry_daily m ON m.report_date = t.report_date
            AND m.batch_id = t.telemetry_batch AND m.prosthesis_id = d.prosthesis_id
        WHERE d.assigned_at < toDateTime(t.report_date + 1, 'UTC')
        GROUP BY d.user_id, t.report_date, d.client_id, d.display_name, d.prosthesis_id, d.model,
                 d.is_period, t.telemetry_batch
    ),
    daily_versions AS (
        -- Hash each day's rows once, then expand the same rows for publication.
        SELECT report_date,
               arraySort(groupArray(tuple(user_id, client_id, display_name, prosthesis_id,
                   model, samples, successful_movements, avg_response_ms, min_battery_pct,
                   is_period, toString(telemetry_batch)))) AS report_rows,
               hex(SHA256(toJSONString(report_rows))) AS batch_id
        FROM metrics GROUP BY report_date
    )
SELECT row.1 AS user_id, report_date, batch_id,
       row.2 AS client_id, row.3 AS display_name, row.4 AS prosthesis_id, row.5 AS model,
       row.6 AS samples, row.7 AS successful_movements,
       row.8 AS avg_response_ms, row.9 AS min_battery_pct, row.10 AS is_period,
       assumeNotNull((SELECT checked_at FROM barrier)) AS cdc_checked_at
FROM daily_versions
ARRAY JOIN report_rows AS row
WHERE (SELECT checked_at FROM barrier) >= now64(6, 'UTC') - INTERVAL 60 SECOND
SETTINGS join_use_nulls = 0;
