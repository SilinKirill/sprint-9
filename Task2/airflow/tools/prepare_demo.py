"""Bind demo data to the existing user and run the DAG for two complete UTC days."""
import os
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone

import psycopg2
from airflow.models.dag import DagModel
from airflow.models.dagrun import DagRun
from airflow.utils.session import create_session

with psycopg2.connect(os.environ['KEYCLOAK_DB_DSN']) as connection:
    with connection.cursor() as cursor:
        cursor.execute("""SELECT id FROM user_entity WHERE username = 'prothetic1'
            AND realm_id = (SELECT id FROM realm WHERE name = 'reports-realm')""")
        user = cursor.fetchone()
if user is None:
    raise RuntimeError('prothetic1 is missing; complete Keycloak setup first')
subprocess.run([sys.executable, '/opt/airflow/tools/seed_demo.py', '--subject', user[0]], check=True)
today = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
start = today - timedelta(days=2)
first = start.date().isoformat()
last = (today - timedelta(days=1)).date().isoformat()


def states():
    with create_session() as session:
        return [run.state for run in session.query(DagRun).filter(
            DagRun.dag_id == 'bionicpro_daily_reports',
            DagRun.execution_date >= start, DagRun.execution_date < today).all()]


# The scheduler may already be processing yesterday when the first startup finishes.
# Airflow backfill returns 0 even if it refuses to replace an active scheduled run.
for attempt in range(90):
    if not any(state in ('running', 'queued') for state in states()):
        break
    time.sleep(2)
else:
    raise RuntimeError('Scheduled report is still active; retry demo-data after it finishes')
# Resetting old runs queues them again. Pause scheduling so it cannot claim them
# between the reset and the backfill; restore the previous setting even on failure.
with create_session() as session:
    model = session.get(DagModel, 'bionicpro_daily_reports')
    if model is None:
        raise RuntimeError('Report DAG is not loaded yet; wait for the scheduler')
    was_paused = model.is_paused
    model.is_paused = True
try:
    subprocess.run(['airflow', 'dags', 'backfill', '-s', first, '-e', last,
                    '--reset-dagruns', '--yes', 'bionicpro_daily_reports'], check=True)
    if states() != ['success', 'success']:
        raise RuntimeError('Both report days must finish successfully')
finally:
    with create_session() as session:
        session.get(DagModel, 'bionicpro_daily_reports').is_paused = was_paused
print(f'Report period: {first} through {last} (UTC)', flush=True)
