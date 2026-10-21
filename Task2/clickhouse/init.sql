CREATE DATABASE IF NOT EXISTS reports;
CREATE TABLE IF NOT EXISTS reports.daily_reports (
    user_id String,
    report_date Date,
    batch_id UUID,
    client_id String,
    display_name String,
    prosthesis_id String,
    model String,
    samples UInt64,
    successful_movements UInt64,
    avg_response_ms Nullable(Float64),
    min_battery_pct Nullable(Float64)
) ENGINE = MergeTree
PARTITION BY toYYYYMM(report_date)
ORDER BY (user_id, report_date, batch_id, prosthesis_id);

-- Append a publication only after every batch row has been written.
CREATE TABLE IF NOT EXISTS reports.completed_periods (
    report_date Date,
    batch_id UUID,
    version UInt64
) ENGINE = ReplacingMergeTree(version)
ORDER BY report_date;

CREATE USER IF NOT EXISTS reports_reader IDENTIFIED WITH sha256_password BY 'reports_reader_demo';
GRANT SELECT ON reports.* TO reports_reader;
CREATE USER IF NOT EXISTS reports_etl IDENTIFIED WITH sha256_password BY 'reports_etl_demo';
GRANT SELECT, INSERT ON reports.* TO reports_etl;
