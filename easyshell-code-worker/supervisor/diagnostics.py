"""Allowlisted policy differences: no observed values, names, labels or payloads."""

from .control import omitted_mounts_safe, policy


MISSING = object()


def category(value):
    if value is MISSING:
        return "missing"
    if value is None:
        return "null"
    if type(value) is bool:
        return "boolean"
    if type(value) is int:
        return "integer"
    if isinstance(value, str):
        return "empty_string" if not value else "nonempty_string"
    if isinstance(value, list):
        return "empty_array" if not value else "nonempty_array"
    if isinstance(value, dict):
        return "empty_object" if not value else "nonempty_object"
    return "other"


def policy_diff(raw, record, image):
    expected = policy(image, record)
    differences = []

    def compare(path, wanted, observed, nullable=False):
        normalized = wanted if nullable and observed is None else observed
        if normalized != wanted or type(normalized) is not type(wanted):
            differences.append({"field": path, "expected_category": category(wanted),
                                "actual_category": category(observed)})

    # Never enumerate keys from Inspect: a hostile key could itself contain a secret.
    for section in ("Config", "HostConfig"):
        actual = raw.get(section)
        if not isinstance(actual, dict):
            differences.append({"field": section, "expected_category": "nonempty_object",
                                "actual_category": category(raw.get(section, MISSING))})
            continue
        wanted = expected if section == "Config" else expected["HostConfig"]
        keys = ("Image", "Cmd", "Entrypoint", "User", "Env", "WorkingDir", "NetworkDisabled",
                "OpenStdin", "StdinOnce", "Tty", "AttachStdin", "AttachStdout", "AttachStderr")
        for key in keys if section == "Config" else wanted:
            if (section == "HostConfig" and key == "Mounts" and key not in actual
                    and omitted_mounts_safe(raw)):
                continue
            value = wanted[key]
            nullable = (key in ("Entrypoint", "Env") if section == "Config" else value in ([], {}))
            compare(section + "." + key, value, actual.get(key, MISSING), nullable)
        if section == "Config" and actual.get("Volumes"):
            compare("Config.Volumes", {}, actual["Volumes"])
        if section == "HostConfig" and actual.get("Runtime") not in (None, "", "runc"):
            compare("HostConfig.Runtime", "runc", actual["Runtime"])
    compare("Mounts", [], raw.get("Mounts", MISSING))
    return differences
