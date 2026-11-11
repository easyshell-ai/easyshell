"""Trusted host-side cgroup v2 reads; never uses a guest-reported path or PID."""

import re
import time
from pathlib import Path

from .control import MEMORY, Rejected


class HostGuard:
    def __init__(self, proc=Path("/proc"), cgroup=Path("/sys/fs/cgroup"), clock=time.monotonic):
        self.proc, self.cgroup, self.clock = Path(proc), Path(cgroup), clock

    @staticmethod
    def read(path):
        with path.open("r", encoding="ascii") as stream:
            text = stream.read(16385)
        if len(text) > 16384:
            raise Rejected("HOST_LIMITS_UNVERIFIED")
        return text.strip()

    def identity(self, pid, cid):
        base = self.proc / str(pid)
        stat = self.read(base / "stat")
        # comm may contain spaces and parentheses; fields after final ')' begin at field 3.
        tail = stat[stat.rfind(")") + 2:].split()
        if stat.split(" ", 1)[0] != str(pid) or len(tail) < 20 or not tail[19].isdigit():
            raise Rejected("HOST_LIMITS_UNVERIFIED")
        entry = self.read(base / "cgroup")
        if not entry.startswith("0::/") or "\n" in entry:
            raise Rejected("HOST_LIMITS_UNVERIFIED")
        relative = entry[4:]
        parts = relative.split("/")
        if (not relative or any(p in ("", ".", "..") for p in parts)
                or not any(p in (cid, "docker-" + cid + ".scope") for p in parts)):
            raise Rejected("HOST_LIMITS_UNVERIFIED")
        path = self.cgroup.joinpath(*parts)
        root = self.cgroup.resolve(strict=True)
        resolved = path.resolve(strict=True)
        if root not in resolved.parents or resolved != path:
            raise Rejected("HOST_LIMITS_UNVERIFIED")
        return tail[19], entry, resolved

    def verify(self, raw, deadline):
        try:
            cid, state = raw["Id"], raw["State"]
            pid = state["Pid"]
            if (not re.fullmatch(r"[0-9a-f]{64}", cid) or type(pid) is not int or pid <= 0
                    or state.get("Running") is not True or self.clock() >= deadline):
                raise Rejected("HOST_LIMITS_UNVERIFIED")
            before = self.identity(pid, cid)
            directory = before[2]
            values = {name: self.read(directory / name) for name in
                      ("memory.max", "memory.swap.max", "pids.max", "cpu.max", "cgroup.procs")}
            if (values["memory.max"] != str(MEMORY) or values["memory.swap.max"] != "0"
                    or values["pids.max"] != "128" or values["cpu.max"].split() != ["100000", "100000"]
                    or str(pid) not in values["cgroup.procs"].split()
                    or before != self.identity(pid, cid) or self.clock() >= deadline):
                raise Rejected("HOST_LIMITS_UNVERIFIED")
        except (OSError, ValueError, KeyError, IndexError, TypeError):
            raise Rejected("HOST_LIMITS_UNVERIFIED") from None

    def save(self, raw, journal):
        if journal is None:
            raise Rejected("HOST_LIMITS_UNVERIFIED")
        directory = self.identity(raw["State"]["Pid"], raw["Id"])[2]
        info = directory.stat()
        with journal.db:
            expected = (str(directory), info.st_dev, info.st_ino)
            previous = journal.db.execute("SELECT path,device,inode FROM guards WHERE container_id=?",
                                          (raw["Id"],)).fetchone()
            if previous is not None and previous != expected:
                raise Rejected("HOST_LIMITS_UNVERIFIED")
            journal.db.execute("INSERT OR IGNORE INTO guards VALUES (?, ?, ?, ?)", (raw["Id"], *expected))

    def confirm_empty(self, cid, journal):
        row = journal.db.execute("SELECT path,device,inode FROM guards WHERE container_id=?", (cid,)).fetchone()
        if row is None:
            raise Rejected("CGROUP_CLEANUP_UNVERIFIED")
        directory = Path(row[0])
        root = self.cgroup.resolve(strict=True)
        if root not in directory.parents or not any(p in (cid, "docker-" + cid + ".scope") for p in directory.parts):
            raise Rejected("CGROUP_CLEANUP_UNVERIFIED")
        try:
            info = directory.stat()
        except FileNotFoundError:
            return
        if directory.resolve(strict=True) != directory or (info.st_dev, info.st_ino) != (row[1], row[2]):
            raise Rejected("CGROUP_CLEANUP_UNVERIFIED")
        events = dict(line.split() for line in self.read(directory / "cgroup.events").splitlines())
        if events.get("populated") != "0" or self.read(directory / "cgroup.procs"):
            raise Rejected("CGROUP_CLEANUP_UNVERIFIED")
