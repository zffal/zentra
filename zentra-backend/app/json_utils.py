"""JSON safety helpers: keep every response valid and predictable.

FastAPI/Starlette's default JSONResponse serializes with ``allow_nan=False``,
so a single non-finite float (NaN/Infinity) produced by a collector would raise
``ValueError`` and turn an otherwise healthy endpoint into a 500. The WebSocket
path (``send_json``) instead uses ``json.dumps`` with ``allow_nan=True``, which
emits the non-standard tokens ``NaN``/``Infinity`` and breaks strict clients
(Android kotlinx.serialization / org.json throw on them).

``sanitize_json`` normalizes every non-finite float to ``null`` *before*
serialization. Finite values and the overall payload shape are preserved, so
consumers never receive invalid JSON and one bad reading cannot take down the
metrics pipeline.
"""

import math
from typing import Any

from fastapi.responses import JSONResponse


def sanitize_json(obj: Any) -> Any:
    """Recursively replace NaN/Infinity floats with None. Pure, non-mutating."""
    if isinstance(obj, float):
        return obj if math.isfinite(obj) else None
    if isinstance(obj, dict):
        return {key: sanitize_json(value) for key, value in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [sanitize_json(value) for value in obj]
    return obj


class SafeJSONResponse(JSONResponse):
    """JSONResponse that guarantees finite JSON output."""

    def render(self, content: Any) -> bytes:
        return super().render(sanitize_json(content))
