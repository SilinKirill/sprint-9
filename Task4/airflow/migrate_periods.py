"""Load telemetry for already published Task2 dates, without changing old reports."""
import json
import sys
from datetime import datetime, timedelta, timezone

sys.path.insert(0, '/opt/airflow/dags')
from report_etl import clickhouse, prepare_report

days = json.loads(clickhouse("""
    SELECT DISTINCT report_date FROM reports.completed_periods
    WHERE report_date NOT IN (SELECT report_date FROM reports.telemetry_periods)
    ORDER BY report_date FORMAT JSON
"""))['data']
for row in days:
    start = datetime.fromisoformat(row['report_date']).replace(tzinfo=timezone.utc)
    prepare_report(start, start + timedelta(days=1))
print(f'Telemetry migrated: {len(days)} complete days')
