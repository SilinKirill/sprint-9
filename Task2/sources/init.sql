-- Minimal educational CRM and telemetry sources. No real patient data.
CREATE SCHEMA crm;
CREATE SCHEMA telemetry;
CREATE TABLE crm.clients (
    client_id text PRIMARY KEY,
    user_id text NOT NULL UNIQUE,
    display_name text NOT NULL
);
CREATE TABLE crm.prostheses (
    prosthesis_id text PRIMARY KEY,
    client_id text NOT NULL REFERENCES crm.clients(client_id),
    model text NOT NULL,
    assigned_at timestamptz NOT NULL
);
-- Ownership is immutable in this educational source.
CREATE TABLE telemetry.measurements (
    event_id text PRIMARY KEY,
    prosthesis_id text NOT NULL REFERENCES crm.prostheses(prosthesis_id),
    recorded_at timestamptz NOT NULL,
    response_ms double precision NOT NULL CHECK (response_ms >= 0),
    battery_pct double precision NOT NULL CHECK (battery_pct BETWEEN 0 AND 100),
    successful boolean NOT NULL
);
CREATE INDEX ON telemetry.measurements (recorded_at, prosthesis_id);
CREATE ROLE etl_source LOGIN PASSWORD 'etl_source_demo';
GRANT CONNECT ON DATABASE sources TO etl_source;
GRANT USAGE ON SCHEMA crm, telemetry TO etl_source;
GRANT SELECT ON ALL TABLES IN SCHEMA crm, telemetry TO etl_source;
