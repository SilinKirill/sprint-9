import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "dags"))
import report_etl


class ReportEtlTest(unittest.TestCase):
    def setUp(self):
        self.start = datetime(2026, 10, 1, tzinfo=timezone.utc)
        self.end = self.start + timedelta(days=1)

    def test_incomplete_and_misaligned_intervals_rejected_before_extraction(self):
        with patch.object(report_etl.psycopg2, "connect") as connect:
            for start, end in [
                (self.start, self.end + timedelta(days=1)),
                (self.start + timedelta(hours=1), self.end + timedelta(hours=1)),
                (datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0),
                 datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=1)),
            ]:
                with self.assertRaises(ValueError):
                    report_etl.prepare_report(start, end)
            connect.assert_not_called()

    @patch.dict("os.environ", {"SOURCE_DSN": "test"})
    def test_failed_upload_never_publishes_and_retry_uses_new_generation(self):
        connection = MagicMock()
        cursor = connection.__enter__.return_value.cursor.return_value.__enter__.return_value
        cursor.fetchmany.side_effect = [[{"event_id": "m1", "recorded_at": self.start}], []]
        written = []

        def fail_after_write(sql, rows):
            written.append(rows[0].copy())
            raise OSError("Upload interrupted")

        with patch.object(report_etl.psycopg2, "connect", return_value=connection):
            with patch.object(report_etl, "clickhouse", side_effect=fail_after_write) as upload:
                with self.assertRaises(OSError):
                    report_etl.prepare_report(self.start, self.end)
                self.assertEqual(1, upload.call_count)
                self.assertIn("telemetry_daily", upload.call_args.args[0])
            cursor.fetchmany.side_effect = [[{"event_id": "m1", "recorded_at": self.start}], []]
            with patch.object(report_etl, "clickhouse") as upload:
                report_etl.prepare_report(self.start, self.end)
                self.assertEqual(2, upload.call_count)
                first, last = upload.call_args_list
                self.assertNotEqual(written[0]["batch_id"], first.args[1][0]["batch_id"])
                self.assertEqual(first.args[1][0]["batch_id"], last.args[1][0]["batch_id"])
                self.assertIn("telemetry_periods", last.args[0])
                sql = cursor.execute.call_args.args[0]
                self.assertNotIn("crm.", sql)
                self.assertNotIn("JOIN", sql)

    @patch.dict("os.environ", {"SOURCE_DSN": "test"})
    def test_empty_source_is_a_completed_period(self):
        connection = MagicMock()
        connection.__enter__.return_value.cursor.return_value.__enter__.return_value.fetchmany.return_value = []
        with patch.object(report_etl.psycopg2, "connect", return_value=connection):
            with patch.object(report_etl, "clickhouse") as upload:
                report_etl.prepare_report(self.start, self.end)
                self.assertEqual(1, upload.call_count)
                self.assertIn("telemetry_periods", upload.call_args.args[0])

    def test_schedule_and_xcom(self):
        self.assertEqual("0 0 * * *", report_etl.dag.schedule_interval)
        self.assertEqual(1, report_etl.dag.max_active_runs)
        self.assertFalse(report_etl.dag.get_task("prepare_and_publish").do_xcom_push)


if __name__ == "__main__":
    unittest.main()
