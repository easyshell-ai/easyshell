"""Docker non-TTY raw multiplex stream, with a single absolute deadline."""

import socket
import time

from .control import MAX_OUTPUT, MAX_REQUEST, Rejected


class Attach:
    def __init__(self, sock, deadline, clock=time.monotonic):
        self.sock, self.deadline, self.clock = sock, deadline, clock
        self.used = False
        self.closed = False
        self.delivered = False

    def remaining(self):
        remaining = self.deadline - self.clock()
        if remaining <= 0:
            raise Rejected("TIMEOUT")
        self.sock.settimeout(remaining)

    def send(self, data):
        view = memoryview(data)
        while view:
            self.remaining()
            sent = self.sock.send(view)
            if sent <= 0:
                raise Rejected("ATTACH_CONNECTION_FAILED")
            view = view[sent:]

    def exact(self, size, eof=False):
        data = bytearray()
        while len(data) < size:
            self.remaining()
            chunk = self.sock.recv(size - len(data))
            if not chunk:
                if eof and not data:
                    return None
                raise Rejected("ATTACH_MALFORMED")
            data.extend(chunk)
        return bytes(data)

    def handshake(self, route):
        try:
            self.send(("POST " + route + " HTTP/1.1\r\nHost: localhost\r\n"
                       "Connection: Upgrade\r\nUpgrade: tcp\r\nContent-Length: 0\r\n\r\n").encode("ascii"))
            header = bytearray()
            while not header.endswith(b"\r\n\r\n"):
                if len(header) >= 8192:
                    raise Rejected("ATTACH_MALFORMED")
                header.extend(self.exact(1))
            lines = bytes(header[:-4]).split(b"\r\n")
            if lines[0] != b"HTTP/1.1 101 UPGRADED" and lines[0] != b"HTTP/1.1 101 Switching Protocols":
                raise Rejected("ATTACH_UPGRADE_FAILED")
            headers = {}
            for line in lines[1:]:
                key, separator, value = line.partition(b":")
                key = key.strip().lower()
                if not separator or not key or key in headers:
                    raise Rejected("ATTACH_MALFORMED")
                headers[key] = value.strip().lower()
            if (headers.get(b"upgrade") != b"tcp" or headers.get(b"connection") != b"upgrade"
                    or headers.get(b"content-type") not in (
                        b"application/vnd.docker.raw-stream",
                        b"application/vnd.docker.multiplexed-stream")
                    or b"transfer-encoding" in headers or b"content-length" in headers):
                raise Rejected("ATTACH_UPGRADE_FAILED")
        except (TimeoutError, socket.timeout):
            raise Rejected("TIMEOUT") from None
        except OSError:
            raise Rejected("ATTACH_CONNECTION_FAILED") from None

    def exchange(self, payload):
        if self.used or self.closed:
            raise Rejected("ATTACH_ALREADY_USED")
        self.used = True
        if not isinstance(payload, bytes) or len(payload) > MAX_REQUEST:
            raise Rejected("REQUEST_TOO_LARGE")
        try:
            self.send(payload)
            self.remaining()
            self.sock.shutdown(socket.SHUT_WR)
            self.delivered = True
            observer = getattr(self, "on_delivered", None)
            if observer is not None:
                observer()
            output = bytearray()
            # Bound stderr and framing as well, including zero-length frame floods.
            wire_bytes, frames = 0, 0
            while True:
                header = self.exact(8, eof=True)
                if header is None:
                    self.remaining()
                    return bytes(output)
                frames += 1
                wire_bytes += 8
                stream, reserved, size = header[0], header[1:4], int.from_bytes(header[4:], "big")
                if stream not in (1, 2) or reserved != b"\0\0\0":
                    raise Rejected("ATTACH_MALFORMED")
                if size > MAX_OUTPUT or frames > 4096 or wire_bytes + size > MAX_OUTPUT * 3:
                    raise Rejected("OUTPUT_TOO_LARGE")
                if stream == 1 and len(output) + size > MAX_OUTPUT:
                    raise Rejected("OUTPUT_TOO_LARGE")
                # Never retain or decode stderr, even on malformed/failed requests.
                left = size
                while left:
                    chunk = self.exact(min(left, 4096))
                    if stream == 1:
                        output.extend(chunk)
                    left -= len(chunk)
                wire_bytes += size
        except (TimeoutError, socket.timeout):
            raise Rejected("TIMEOUT") from None
        except OSError:
            raise Rejected("ATTACH_CONNECTION_FAILED") from None

    def close(self):
        if not self.closed:
            self.closed = True
            try:
                self.sock.close()
            except OSError:
                raise Rejected("ATTACH_CLOSE_FAILED") from None
