\set ON_ERROR_STOP on
\getenv cdc_password CDC_PASSWORD
SELECT 'CREATE ROLE crm_cdc LOGIN REPLICATION'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_cdc') \gexec
SELECT format('ALTER ROLE crm_cdc PASSWORD %L', :'cdc_password') \gexec
GRANT CONNECT ON DATABASE sources TO crm_cdc;
GRANT USAGE ON SCHEMA crm TO crm_cdc;
GRANT SELECT ON crm.clients, crm.prostheses TO crm_cdc;
ALTER TABLE crm.clients REPLICA IDENTITY FULL;
ALTER TABLE crm.prostheses REPLICA IDENTITY FULL;
CREATE TABLE IF NOT EXISTS crm.cdc_heartbeat (
    id integer PRIMARY KEY CHECK (id = 1),
    checked_at timestamptz NOT NULL
);
INSERT INTO crm.cdc_heartbeat VALUES (1, clock_timestamp()) ON CONFLICT DO NOTHING;
GRANT SELECT, UPDATE ON crm.cdc_heartbeat TO crm_cdc;
SELECT 'CREATE PUBLICATION bionicpro_crm FOR TABLE crm.clients, crm.prostheses, crm.cdc_heartbeat'
WHERE NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'bionicpro_crm') \gexec
ALTER PUBLICATION bionicpro_crm SET TABLE crm.clients, crm.prostheses, crm.cdc_heartbeat;
