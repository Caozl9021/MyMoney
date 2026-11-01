import pytest

from app.config import Settings


def test_database_connection_must_be_explicit(monkeypatch):
    monkeypatch.setenv("MYMONEY_DB_SCHEMA", "mymoney_home_test")
    monkeypatch.delenv("MYMONEY_DATABASE_URL", raising=False)
    with pytest.raises(RuntimeError, match="MYMONEY_DATABASE_URL"):
        Settings.from_env()


def test_database_connection_cannot_be_blank(monkeypatch):
    monkeypatch.setenv("MYMONEY_DB_SCHEMA", "mymoney_home_test")
    monkeypatch.setenv("MYMONEY_DATABASE_URL", "  ")
    with pytest.raises(RuntimeError, match="MYMONEY_DATABASE_URL"):
        Settings.from_env()
