#!/bin/sh
set -eu
mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc mb --ignore-existing local/reports >/dev/null
mc anonymous set none local/reports >/dev/null
mc admin user add local "$REPORTS_S3_ACCESS_KEY" "$REPORTS_S3_SECRET_KEY" >/dev/null
mc admin policy create local reports-app /setup/policy.json >/dev/null
mc admin policy attach local reports-app --user "$REPORTS_S3_ACCESS_KEY" >/dev/null
echo 'Private reports bucket is ready.'
