import base64
import hashlib
import json
import logging
import os
import re
import secrets
import threading
import time
from typing import Dict, Optional

import jwt
from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from config import DATA_DIR, settings

logger = logging.getLogger("zentra.auth")

USERS_FILE = DATA_DIR / "users.json"

PBKDF2_ITERATIONS = 310_000
_bearer = HTTPBearer(auto_error=False)

_users_lock = threading.Lock()
_token_blacklock = threading.Lock()
_token_blacklist: Dict[str, float] = {}

_login_attempts: Dict[str, list] = {}
_LOGIN_WINDOW_SECONDS = 60
_LOGIN_MAX_ATTEMPTS = 8


def hash_password(password: str) -> str:
    if len(password) < 6:
        raise ValueError("password must be at least 6 characters")
    salt = secrets.token_bytes(16)
    derived = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, PBKDF2_ITERATIONS
    )
    return "pbkdf2_sha256${}${}${}".format(
        PBKDF2_ITERATIONS,
        base64.b64encode(salt).decode("ascii"),
        base64.b64encode(derived).decode("ascii"),
    )


def verify_password(password: str, stored: str) -> bool:
    try:
        scheme, iterations, salt_b64, hash_b64 = stored.split("$")
        if scheme != "pbkdf2_sha256":
            return False
        salt = base64.b64decode(salt_b64)
        expected = base64.b64decode(hash_b64)
        derived = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), salt, int(iterations)
        )
        return secrets.compare_digest(derived, expected)
    except Exception:
        return False


def _load_users() -> dict:
    if not USERS_FILE.exists():
        return {}
    try:
        with USERS_FILE.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        logger.error("event=users_load_failed file=%s", USERS_FILE)
        return {}


def _save_users(users: dict) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    tmp = USERS_FILE.with_suffix(".json.tmp")
    with tmp.open("w", encoding="utf-8") as handle:
        json.dump(users, handle, indent=2)
    os.chmod(tmp, 0o600)
    tmp.replace(USERS_FILE)


def ensure_initial_user() -> None:
    if not settings.AUTO_ADMIN:
        logger.info(
            "event=admin_bootstrap_disabled "
            "message=no default account created; first registered user becomes admin"
        )
        return
    with _users_lock:
        users = _load_users()
        username = settings.ADMIN_USERNAME
        if username in users:
            return
        password = settings.INITIAL_PASSWORD
        generated = False
        if not password or len(password) < 8:
            password = secrets.token_urlsafe(16)
            generated = True
        users[username] = {
            "password_hash": hash_password(password),
            "role": "admin",
            "created_at": int(time.time()),
        }
        _save_users(users)
        if generated:
            logger.warning(
                "event=initial_user_created username=%s password=%s "
                "(set ZENTRA_INITIAL_PASSWORD in /opt/zentra/.env to choose your own; "
                "password shown once only)",
                username,
                password,
            )
        else:
            logger.info("event=initial_user_created username=%s", username)


def register_user(username: str, password: str, role: str = "user") -> dict:
    username = username.strip()
    if len(username) < 3 or len(username) > 64:
        raise ValueError("username must be between 3 and 64 characters")
    if not re.match(r"^[a-zA-Z0-9_\-\.]+$", username):
        raise ValueError("username can only contain letters, numbers, underscores, dashes, and dots")
    if len(password) < 6:
        raise ValueError("password must be at least 6 characters")

    with _users_lock:
        users = _load_users()
        if username in users:
            raise ValueError("username already exists")
        # If no users exist, assign admin; otherwise user
        assigned_role = "admin" if len(users) == 0 else role
        now_ts = int(time.time())
        users[username] = {
            "password_hash": hash_password(password),
            "role": assigned_role,
            "created_at": now_ts,
        }
        _save_users(users)
        logger.info("event=user_registered username=%s role=%s", username, assigned_role)
        return {
            "username": username,
            "role": assigned_role,
            "created_at": str(now_ts),
        }


def delete_user(username: str) -> bool:
    with _users_lock:
        users = _load_users()
        if username in users:
            del users[username]
            _save_users(users)
            logger.info("event=user_deleted username=%s", username)
            return True
        return False


def authenticate(username: str, password: str) -> Optional[dict]:
    users = _load_users()
    entry = users.get(username)
    if not entry:
        verify_password(password, hash_password("dummy-password-for-timing"))
        return None
    if not verify_password(password, entry.get("password_hash", "")):
        return None
    return {
        "username": username,
        "role": entry.get("role", "user"),
        "created_at": str(entry.get("created_at", "")),
    }


def change_password(username: str, current: str, new: str) -> None:
    with _users_lock:
        users = _load_users()
        entry = users.get(username)
        if not entry or not verify_password(current, entry.get("password_hash", "")):
            raise ValueError("current password is incorrect")
        if len(new) < 6:
            raise ValueError("new password must be at least 6 characters")
        entry["password_hash"] = hash_password(new)
        entry["password_changed_at"] = int(time.time())
        _save_users(users)
    logger.info("event=password_changed username=%s", username)


def create_token(user: dict) -> dict:
    now = int(time.time())
    expires = now + settings.JWT_EXPIRE_MINUTES * 60
    jti = secrets.token_hex(8)
    payload = {
        "sub": user["username"],
        "role": user.get("role", "user"),
        "iat": now,
        "exp": expires,
        "jti": jti,
    }
    token = jwt.encode(payload, settings.JWT_SECRET, algorithm=settings.JWT_ALGORITHM)
    return {"access_token": token, "token_type": "bearer", "expires_at": expires}


def decode_token(token: str) -> Optional[dict]:
    try:
        payload = jwt.decode(
            token, settings.JWT_SECRET, algorithms=[settings.JWT_ALGORITHM]
        )
    except jwt.ExpiredSignatureError:
        return None
    except jwt.InvalidTokenError:
        return None
    jti = payload.get("jti")
    if jti:
        with _token_blacklock:
            if jti in _token_blacklist:
                return None
    return payload


def blacklist_token(payload: dict) -> None:
    jti = payload.get("jti")
    if not jti:
        return
    with _token_blacklock:
        now = time.time()
        expired = [k for k, v in _token_blacklist.items() if v < now]
        for key in expired:
            _token_blacklist.pop(key, None)
        _token_blacklist[jti] = float(payload.get("exp", now))


def _client_ip(request: Request) -> str:
    client = request.client
    return client.host if client else "unknown"


def _rate_limited(request: Request) -> bool:
    ip = _client_ip(request)
    now = time.time()
    hits = [t for t in _login_attempts.get(ip, []) if now - t < _LOGIN_WINDOW_SECONDS]
    hits.append(now)
    _login_attempts[ip] = hits
    return len(hits) > _LOGIN_MAX_ATTEMPTS


def get_current_user(
    request: Request,
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(_bearer),
) -> Optional[dict]:
    if credentials is None:
        token = request.query_params.get("token")
        if not token:
            return None
        payload = decode_token(token)
    else:
        payload = decode_token(credentials.credentials)
    if payload is None:
        if settings.REQUIRE_AUTH_FOR_READS:
            raise HTTPException(status_code=401, detail="invalid or expired token")
        return None
    return {
        "username": payload.get("sub"),
        "role": payload.get("role", "user"),
        "created_at": payload.get("iat"),
    }


def require_auth(request: Request) -> dict:
    header = request.headers.get("Authorization", "")
    token = ""
    if header.lower().startswith("bearer "):
        token = header[7:].strip()
    if not token:
        token = request.query_params.get("token", "")
    payload = decode_token(token) if token else None
    if payload is None:
        raise HTTPException(status_code=401, detail="authentication required")
    return {
        "username": payload.get("sub"),
        "role": payload.get("role", "user"),
        "created_at": payload.get("iat"),
    }


def require_read(request: Request) -> Optional[dict]:
    if not settings.REQUIRE_AUTH_FOR_READS:
        return None
    return require_auth(request)
