"""Verify and restore the owned missing-media fixture's Android environment."""

import re


def set_metered(serial, network, mode, *, adb):
    # AOSP applies this command but returns -1. Verify the actual configuration.
    result = adb(
        serial,
        "shell",
        "cmd",
        "netpolicy",
        "set",
        "metered-network",
        network,
        mode,
        check=False,
    )
    rows = (
        adb(serial, "shell", "cmd", "netpolicy", "list", "wifi-networks")
        .stdout.decode()
        .splitlines()
    )
    actual = [
        line.rsplit(";", 1)[1] for line in rows if line.rsplit(";", 1)[0] == network
    ]
    expected = "none" if mode == "undefined" else mode
    if (
        result.returncode not in [0, 255]
        or not actual
        or any(value != expected for value in actual)
    ):
        raise RuntimeError(
            "Metered-network configuration did not match the requested state"
        )


def restore_environment(
    serial,
    network,
    original,
    saver,
    airplane,
    wifi,
    grants,
    *,
    adb,
    safe_target,
    set_metered,
    package_name,
    sleep,
):
    errors = []

    def attempt(label, operation):
        try:
            operation()
        except Exception as error:
            errors.append(f"{label}: {type(error).__name__}")

    safe_target(serial)
    steps = []
    if original is not None:
        steps.append(
            (
                "metered override",
                lambda: set_metered(
                    serial, network, "undefined" if original == "none" else original
                ),
            )
        )
    steps.extend(
        [
            (
                "Data Saver",
                lambda: adb(
                    serial,
                    "shell",
                    "cmd",
                    "netpolicy",
                    "set",
                    "restrict-background",
                    str(saver).lower(),
                ),
            ),
            (
                "airplane mode",
                lambda: adb(
                    serial,
                    "shell",
                    "cmd",
                    "connectivity",
                    "airplane-mode",
                    "enable" if airplane else "disable",
                ),
            ),
            (
                "Wi-Fi",
                lambda: adb(
                    serial,
                    "shell",
                    "svc",
                    "wifi",
                    "disable" if wifi == b"0" else "enable",
                ),
            ),
        ]
    )
    for label, operation in steps:
        attempt(label, operation)

    def restore_grant(permission, granted):
        result = adb(
            serial,
            "shell",
            "pm",
            "grant" if granted else "revoke",
            package_name,
            "android.permission." + permission,
            check=False,
        )
        if result.returncode and (
            granted
            or b"has not requested permission" not in result.stderr + result.stdout
        ):
            raise RuntimeError("Permission grant was not restored")

    for permission, granted in grants.items():
        attempt(
            permission,
            lambda permission=permission, granted=granted: restore_grant(
                permission, granted
            ),
        )

    def verify():
        for _ in range(20):
            actual_airplane = (
                adb(
                    serial, "shell", "settings", "get", "global", "airplane_mode_on"
                ).stdout.strip()
                == b"1"
            )
            actual_wifi = (
                adb(
                    serial, "shell", "settings", "get", "global", "wifi_on"
                ).stdout.strip()
                != b"0"
            )
            if actual_airplane == airplane and actual_wifi == (wifi != b"0"):
                break
            sleep(0.25)
        else:
            raise RuntimeError("Radio settings were not restored")
        actual_saver = (
            b"enabled"
            in adb(
                serial, "shell", "cmd", "netpolicy", "get", "restrict-background"
            ).stdout
        )
        package = adb(
            serial, "shell", "dumpsys", "package", package_name
        ).stdout.decode()
        actual_grants = {
            permission: bool(
                re.search(
                    r"android\.permission\." + permission + r": granted=true", package
                )
            )
            for permission in grants
        }
        if actual_saver != saver or actual_grants != grants:
            raise RuntimeError("Data Saver or permission grants were not restored")

    attempt("final state readback", verify)
    if errors:
        raise RuntimeError(
            "Emulator environment restoration failed: " + "; ".join(errors)
        )
