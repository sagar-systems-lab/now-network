#!/usr/bin/env python3
"""Reject phone APKs that do not contain the hosted public client configuration."""
import re
import sys
import zipfile


def verify(path: str) -> None:
    with zipfile.ZipFile(path) as apk:
        dex = b"\n".join(apk.read(name) for name in apk.namelist()
                         if re.fullmatch(r"classes\d*\.dex", name))
    required = (
        rb"https://[a-z0-9]+\.supabase\.co/functions/v1/now-api",
        rb"sb_publishable_[A-Za-z0-9_-]{16,}",
    )
    if not dex or any(re.search(pattern, dex) is None for pattern in required):
        raise SystemExit("APK has no hosted client configuration. Rebuild with -PNOW_RUNTIME=hosted.")
    print("Connected APK public configuration verified")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify-connected-apk.py <apk>")
    verify(sys.argv[1])
