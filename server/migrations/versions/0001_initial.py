"""initial home sync schema"""
from alembic import op

revision = "0001_initial"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(open("schema.sql", encoding="utf-8").read())


def downgrade() -> None:
    for table in ("ledger_backups", "sync_operations", "sync_changes", "rule_items", "devices", "sync_spaces"):
        op.execute(f"DROP TABLE IF EXISTS mymoney_home.{table} CASCADE")
    op.execute("DROP SCHEMA IF EXISTS mymoney_home CASCADE")
