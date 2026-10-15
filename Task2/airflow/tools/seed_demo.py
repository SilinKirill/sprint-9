"""Explicit demo fixture bound to an existing Keycloak subject; never creates users."""
import argparse
import os
from datetime import datetime, timedelta, timezone

import psycopg2

parser = argparse.ArgumentParser()
parser.add_argument("--subject", required=True, help="Existing Keycloak sub, not username")
args = parser.parse_args()
if not args.subject.strip() or args.subject == "demo-other-subject":
    parser.error("Use an existing user's non-empty Keycloak sub")

today = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
with psycopg2.connect(os.environ["SOURCE_DSN"]) as connection:
    with connection.cursor() as cursor:
        for client, subject, name in [
            ("demo-client", args.subject, "Демонстрационный клиент"),
            ("demo-other", "demo-other-subject", "Другой демонстрационный клиент"),
        ]:
            cursor.execute("""INSERT INTO crm.clients VALUES (%s,%s,%s)
                ON CONFLICT (client_id) DO NOTHING""", (client, subject, name))
            cursor.execute("SELECT user_id FROM crm.clients WHERE client_id=%s", (client,))
            if cursor.fetchone()[0] != subject:
                raise ValueError("Demo client already belongs to another subject; refusing to reassign")
            for number in (1, 2):
                device = f"{client}-prosthesis-{number}"
                cursor.execute("""INSERT INTO crm.prostheses VALUES (%s,%s,%s,%s)
                    ON CONFLICT DO NOTHING""", (device, client, "BionicPRO Demo", today - timedelta(days=30)))
                for days_ago in (1, 2):
                    for sample in range(3):
                        recorded = today - timedelta(days=days_ago) + timedelta(hours=10, minutes=sample)
                        cursor.execute("""INSERT INTO telemetry.measurements VALUES (%s,%s,%s,%s,%s,%s)
                            ON CONFLICT DO NOTHING""", (
                                f"{device}-{recorded.isoformat()}", device, recorded,
                                80 + sample * 10, 90 - sample * 5, sample != 2))
print("Demo CRM and telemetry ready for the previous two UTC days.")
