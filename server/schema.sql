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

-- W2: 设备账本逐条副本同步（独立于规则六表，不硬塞 rule_items.kind）
CREATE TABLE IF NOT EXISTS ledger_items (
    space_id uuid NOT NULL REFERENCES sync_spaces(id) ON DELETE CASCADE,
    ledger_id uuid NOT NULL,
    id text NOT NULL,
    item_kind text NOT NULL CHECK (item_kind IN ('bill','account','entry')),
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
    payload jsonb NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    content_updated_at bigint,
    updated_by uuid,
    deleted_at timestamptz,
    PRIMARY KEY (space_id, ledger_id, id),
    FOREIGN KEY (space_id, updated_by) REFERENCES devices(space_id, id)
);
CREATE INDEX IF NOT EXISTS ledger_items_kind_deleted_idx ON ledger_items(space_id, ledger_id, item_kind, deleted_at);

CREATE TABLE IF NOT EXISTS ledger_changes (
    space_id uuid NOT NULL REFERENCES sync_spaces(id) ON DELETE CASCADE,
    ledger_id uuid NOT NULL,
    revision bigint NOT NULL,
    item_id text NOT NULL,
    operation text NOT NULL CHECK (operation IN ('put','delete','restore')),
    snapshot jsonb NOT NULL,
    device_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, ledger_id, revision),
    FOREIGN KEY (space_id, device_id) REFERENCES devices(space_id, id)
);
CREATE INDEX IF NOT EXISTS ledger_changes_item_idx ON ledger_changes(space_id, ledger_id, item_id);

CREATE TABLE IF NOT EXISTS ledger_operations (
    space_id uuid NOT NULL REFERENCES sync_spaces(id) ON DELETE CASCADE,
    device_id uuid NOT NULL,
    ledger_id uuid NOT NULL,
    operation_id text NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    item_id text NOT NULL,
    base_revision bigint NOT NULL CHECK (base_revision >= 0),
    result jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, device_id, ledger_id, operation_id),
    FOREIGN KEY (space_id, device_id) REFERENCES devices(space_id, id)
);

CREATE TABLE IF NOT EXISTS ledger_device_cursors (
    space_id uuid NOT NULL REFERENCES sync_spaces(id) ON DELETE CASCADE,
    device_id uuid NOT NULL,
    ledger_id uuid NOT NULL,
    last_pulled_revision bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, device_id, ledger_id),
    FOREIGN KEY (space_id, device_id) REFERENCES devices(space_id, id)
);

-- W3: 跨设备按需导入的冻结来源快照元数据（B 从 A 导入时登记；只读，不参与 A 的修订流）
CREATE TABLE IF NOT EXISTS ledger_import_snapshots (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES sync_spaces(id) ON DELETE CASCADE,
    source_device_id uuid NOT NULL,
    source_ledger_id uuid NOT NULL,
    target_device_id uuid NOT NULL,
    source_revision bigint NOT NULL CHECK (source_revision >= 0),
    item_count integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (space_id, source_device_id) REFERENCES devices(space_id, id),
    FOREIGN KEY (space_id, target_device_id) REFERENCES devices(space_id, id)
);
CREATE INDEX IF NOT EXISTS ledger_import_snapshots_space_idx
    ON ledger_import_snapshots(space_id, target_device_id, created_at DESC);

-- X: stable personal backup sources; old devices and foreign keys remain intact.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS installation_id uuid;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS credential_generation bigint NOT NULL DEFAULT 1;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS archived_at timestamptz;
CREATE UNIQUE INDEX IF NOT EXISTS devices_installation_unique
    ON devices(space_id, installation_id) WHERE installation_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS device_connection_codes (
    code_hash char(64) PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES sync_spaces(id) ON DELETE CASCADE,
    epoch uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    installation_id uuid,
    operation_id uuid,
    grant_hash char(64) UNIQUE,
    grant_expires_at timestamptz
);

-- Z: admin-panel invitation lifecycle. An opaque invitation_id never restores the code;
-- cancelled_at closes a not-yet-completed business authorization (does not revoke a
-- completed device and never touches the Tailscale network key).
ALTER TABLE device_connection_codes ADD COLUMN IF NOT EXISTS invitation_id uuid;
ALTER TABLE device_connection_codes ADD COLUMN IF NOT EXISTS cancelled_at timestamptz;
CREATE UNIQUE INDEX IF NOT EXISTS device_connection_codes_invitation_idx
    ON device_connection_codes(invitation_id) WHERE invitation_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS device_enrollment_operations (
    grant_hash char(64) PRIMARY KEY REFERENCES device_connection_codes(grant_hash),
    space_id uuid NOT NULL,
    source_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    request_hash char(64) NOT NULL,
    credential_hash char(64) NOT NULL,
    result_generation bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (space_id, source_id) REFERENCES devices(space_id, id)
);

-- Y: backup deletion tombstones. Rows stay to block same-id re-upload; the
-- physical file is recycled asynchronously after the transaction commits.
ALTER TABLE ledger_backups ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
ALTER TABLE ledger_backups ADD COLUMN IF NOT EXISTS recycled_at timestamptz;
CREATE INDEX IF NOT EXISTS ledger_backups_live_idx
    ON ledger_backups(space_id, device_id, uploaded_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ledger_backups_recycle_idx
    ON ledger_backups(deleted_at) WHERE deleted_at IS NOT NULL AND recycled_at IS NULL;

CREATE TABLE IF NOT EXISTS backup_delete_operations (
    space_id uuid NOT NULL REFERENCES sync_spaces(id) ON DELETE CASCADE,
    source_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    deleted_backup_ids uuid[] NOT NULL,
    deleted_bytes bigint NOT NULL CHECK (deleted_bytes >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, source_id, operation_id),
    FOREIGN KEY (space_id, source_id) REFERENCES devices(space_id, id)
);
