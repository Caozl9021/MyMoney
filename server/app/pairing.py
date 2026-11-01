from __future__ import annotations

import secrets
import threading
import time

from .config import get_settings


class PairingStore:
    """Single-process pairing codes.

    Defaults to a short-lived, one-time code. Setting ``MYMONEY_PAIRING_TTL_SECONDS=0``
    keeps the configured code valid until it is manually rotated; this is intended
    for a private home server where the user explicitly wants a permanent code.
    A restart still clears an in-memory generated code, but a fixed code from
    ``MYMONEY_PAIRING_CODE`` is re-seeded at startup.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._code: str | None = None
        self._expires_at: float = 0.0
        self._attempts: int = 0
        self._permanent = False

    def issue(self, ttl_seconds: int | None = None) -> str:
        if ttl_seconds is None:
            ttl_seconds = get_settings().pairing_ttl_seconds
        code = get_settings().pairing_code or secrets.token_urlsafe(6).replace("-", "").replace("_", "")[:8].upper()
        with self._lock:
            self._code = code
            self._permanent = ttl_seconds <= 0
            self._expires_at = 0.0 if self._permanent else time.monotonic() + ttl_seconds
            self._attempts = 0
        return code

    def verify(self, code: str) -> bool:
        with self._lock:
            if not self._code:
                return False
            if self._permanent:
                # A permanent code is intentionally reusable and is not invalidated
                # by a typo or by a successful pairing.
                return secrets.compare_digest(self._code, code.strip().upper())
            now = time.monotonic()
            if now >= self._expires_at:
                self._code = None
                return False
            self._attempts += 1
            if self._attempts > 10:
                self._code = None
                return False
            if not secrets.compare_digest(self._code, code.strip().upper()):
                return False
            self._code = None
            return True


PAIRING = PairingStore()
