"""Verify that public staging is serving the revision approved by Hypertext CI."""

import http.client
import json
import os
import re
import sys


def main():
    sha = os.environ.get("HYPERTEXT_CI_SHA", "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        sys.exit("Hypertext CI must supply the full approved commit SHA.")

    connection = http.client.HTTPSConnection("cloud-staging.logdate.app", timeout=30)
    try:
        connection.request("GET", "/health", headers={"Cache-Control": "no-cache"})
        response = connection.getresponse()
        if response.status != 200:
            sys.exit(f"Staging health returned HTTP {response.status}.")
        health = json.loads(response.read(4096))
    finally:
        connection.close()

    if (
        health.get("status") != "healthy"
        or health.get("release") != f"logdate-server@{sha}"
    ):
        sys.exit("Staging must be healthy and serve the exact approved commit.")


if __name__ == "__main__":
    main()
