"""Load complete telemetry days; ClickHouse combines them with CDC CRM data."""
import json
import os
import time
import uuid
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import psycopg2
import psycopg2.extras
from airflow import DAG
from airflow.operators.python import PythonOperator


def clickhouse(sql, rows=None):
    url = os.environ["CLICKHOUSE_URL"] + "/?" + urlencode({
        "query": sql, "wait_end_of_query": "1",
    })
    body = b"" if rows is None else ("\n".join(
        json.dumps(row, allow_nan=False) for row in rows
    ) + "\n").encode()
    request = Request(url, data=body, headers={
        "X-ClickHouse-User": "reports_etl",
        "X-ClickHouse-Key": os.environ["CLICKHOUSE_PASSWORD"],
    })
    with urlopen(request, timeout=120) as response:
        return response.read()


def prepare_report(data_interval_start, data_interval_end, **_):
    start = data_interval_start.astimezone(timezone.utc)
    end = data_interval_end.astimezone(timezone.utc)
    if (start.hour, start.minute, start.second, start.microsecond) != (0, 0, 0, 0):
        raise ValueError("Expected midnight UTC")
    if end - start != timedelta(days=1) or end > datetime.now(timezone.utc):
        raise ValueError("Only a complete past UTC day can be published")

    # A fresh generation per attempt: a failed upload can never become visible.
    batch = str(uuid.uuid4())
    day = start.date().isoformat()
    with psycopg2.connect(os.environ["SOURCE_DSN"]) as connection:
        connection.set_session(readonly=True, isolation_level="REPEATABLE READ")
        with connection.cursor(name="daily_report", cursor_factory=psycopg2.extras.RealDictCursor) as cursor:
            # CRM is delivered by Debezium. No CRM scan or JOIN at the source.
            cursor.execute("""
                SELECT event_id, prosthesis_id, recorded_at, response_ms, battery_pct,
                       successful::integer AS successful
                FROM telemetry.measurements
                WHERE recorded_at >= %s AND recorded_at < %s
                ORDER BY recorded_at, event_id
            """, (start, end))
            while rows := cursor.fetchmany(1000):
                for row in rows:
                    row['recorded_at'] = row['recorded_at'].astimezone(timezone.utc).strftime('%Y-%m-%d %H:%M:%S.%f')
                    row.update(report_date=day, batch_id=batch)
                clickhouse("INSERT INTO reports.telemetry_daily FORMAT JSONEachRow", rows)

    # This is the final operation; an empty source also has a processed period.
    clickhouse("INSERT INTO reports.telemetry_periods FORMAT JSONEachRow", [{
        "report_date": day, "batch_id": batch, "version": time.time_ns(),
    }])


with DAG(
    "bionicpro_daily_reports",
    start_date=datetime(2026, 10, 1, tzinfo=timezone.utc),
    schedule="0 0 * * *",
    catchup=False,
    max_active_runs=1,
    default_args={"owner": "bionicpro", "retries": 2, "retry_delay": timedelta(minutes=2)},
    tags=["reports"],
) as dag:
    PythonOperator(
        task_id="prepare_and_publish",
        python_callable=prepare_report,
        do_xcom_push=False,
        execution_timeout=timedelta(minutes=30),
    )
