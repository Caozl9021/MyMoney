CREATE SCHEMA IF NOT EXISTS mymoney_home;
SET search_path TO mymoney_home, public;

CREATE TABLE IF NOT EXISTS sync_spaces (
    id uuid PRIMARY KEY,
    epoch uuid NOT NULL,
    name text NOT NULL,
    current_revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS devices (
    space_id uuid NOT NULL REFERENCES sync_spaces(id) ON DELETE CASCADE,
    id uuid NOT NULL,
    name text NOT NULL,
    token_hash char(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    last_pulled_revision bigint NOT NULL DEFAULT 0,
    last_seen_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, id)
);

CREATE TABLE IF NOT EXISTS rule_items (
    space_id uuid NOT NULL REFERENCES sync_spaces(id) ON DELETE CASCADE,
    id text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('recognition_rule','merchant_note_rule','official_rule_switch','note_defaults')),
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version = 1),
    payload jsonb NOT NULL,
    natural_key text,
    sort_key text NOT NULL DEFAULT '',
    revision bigint NOT NULL CHECK (revision > 0),
    content_updated_at bigint,
    updated_by uuid,
    deleted_at timestamptz,
    PRIMARY KEY (space_id, id),
    FOREIGN KEY (space_id, updated_by) REFERENCES devices(space_id, id)
);
CREATE INDEX IF NOT EXISTS rule_items_kind_key_idx ON rule_items(space_id, kind, natural_key);
CREATE INDEX IF NOT EXISTS rule_items_kind_deleted_idx ON rule_items(space_id, kind, deleted_at);

CREATE TABLE IF NOT EXISTS sync_changes (
    space_id uuid NOT NULL REFERENCES sync_spaces(id) ON DELETE CASCADE,
    revision bigint NOT NULL,
    item_id text NOT NULL,
    operation text NOT NULL CHECK (operation IN ('put','delete','restore')),
    snapshot jsonb NOT NULL,
    device_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, revision),
    FOREIGN KEY (space_id, device_id) REFERENCES devices(space_id, id)
);

CREATE TABLE IF NOT EXISTS sync_operations (
    space_id uuid NOT NULL REFERENCES sync_spaces(id) ON DELETE CASCADE,
    device_id uuid NOT NULL,
    operation_id text NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    item_id text NOT NULL,
    base_revision bigint NOT NULL CHECK (base_revision >= 0),
    result jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, device_id, operation_id),
    FOREIGN KEY (space_id, device_id) REFERENCES devices(space_id, id)
);

CREATE TABLE IF NOT EXISTS ledger_backups (
    space_id uuid NOT NULL REFERENCES sync_spaces(id) ON DELETE CASCADE,
    id uuid NOT NULL,
    device_id uuid NOT NULL,
    app_version text NOT NULL,
    backup_schema integer NOT NULL CHECK (backup_schema > 0),
    storage_key text NOT NULL UNIQUE,
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    bill_count bigint NOT NULL CHECK (bill_count >= 0),
    snapshot_at timestamptz NOT NULL,
    uploaded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, id),
    FOREIGN KEY (space_id, device_id) REFERENCES devices(space_id, id)
);
CREATE INDEX IF NOT EXISTS ledger_backups_time_idx ON ledger_backups(space_id, snapshot_at DESC);
