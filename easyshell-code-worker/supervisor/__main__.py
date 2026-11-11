"""Bounded, always-disabled CLI. Does not instantiate or contact the Docker adapter."""

import json
import sys

from .control import MAX_REQUEST, Rejected, failed, request


def main(stdin=None, stdout=None):
    stdin = sys.stdin.buffer if stdin is None else stdin
    stdout = sys.stdout if stdout is None else stdout
    try:
        request(stdin.read(MAX_REQUEST + 1))
        response = failed("ISOLATION_UNVERIFIED")
    except Rejected as error:
        response = failed(error.code)
    except Exception:
        response = failed("INVALID_REQUEST")
    stdout.write(json.dumps(response, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
