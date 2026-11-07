from app.connect_code import build_connect_code


def _roundtrip(code: str) -> dict:
    import base64
    import json

    assert code.startswith("MMJOIN2.")
    body = code[len("MMJOIN2."):]
    body += "=" * ((4 - len(body) % 4) % 4)
    return json.loads(base64.urlsafe_b64decode(body))


def test_build_connect_code_v2():
    code = build_connect_code(
        ctrl_url="https://controlplane.tailscale.com",
        auth_key="tskey-auth-kTEST-abc",
        server_url="http://100.65.248.62:18080/",
        pair_code="ABC123XY",
        device_name="小米14",
    )
    payload = _roundtrip(code)
    assert payload["v"] == 2
    assert payload["key"] == "tskey-auth-kTEST-abc"
    assert payload["server"] == "http://100.65.248.62:18080"  # trailing slash trimmed
    assert payload["code"] == "ABC123XY"
    assert payload["name"] == "小米14"


def test_reject_non_tskey():
    import pytest

    with pytest.raises(ValueError):
        build_connect_code(
            ctrl_url="https://c", auth_key="abc", server_url="http://s", pair_code="X"
        )


def test_reject_missing_pair_code():
    import pytest

    with pytest.raises(ValueError):
        build_connect_code(
            ctrl_url="https://c", auth_key="tskey-a", server_url="http://s", pair_code="  "
        )
