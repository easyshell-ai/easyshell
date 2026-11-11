import copy
import socket
import unittest
from dataclasses import replace
from unittest.mock import patch

from .attach import Attach
from .control import Journal, MAX_OUTPUT, NotFound, Rejected, policy, worker_response
from .docker_adapter import Config, DockerAdapter
from .test_control import CID, IMAGE, Clock, FakeRuntime, sample


def frame(stream, data):
    return bytes([stream, 0, 0, 0]) + len(data).to_bytes(4, "big") + data


class Wire:
    def __init__(self, data=b"", clock=None, advance=0, failure=None):
        self.data, self.sent = bytearray(data), bytearray()
        self.clock, self.advance, self.failure = clock, advance, failure
        self.half_closed, self.closed = False, False

    def settimeout(self, timeout):
        if timeout <= 0:
            raise AssertionError("nonpositive timeout")

    def send(self, data):
        if self.failure == "send":
            raise BrokenPipeError()
        amount = min(len(data), 3)
        self.sent.extend(data[:amount])
        return amount

    def recv(self, size):
        if self.failure == "recv":
            raise ConnectionResetError()
        if self.clock:
            self.clock.pause(self.advance)
        chunk = self.data[:min(size, 3)]
        del self.data[:len(chunk)]
        return bytes(chunk)

    def shutdown(self, direction):
        if self.failure == "shutdown":
            raise OSError()
        assert direction == socket.SHUT_WR
        self.half_closed = True

    def close(self):
        self.closed = True
        if self.failure == "close":
            raise OSError()


class AttachTest(unittest.TestCase):
    def test_fragmented_stdout_and_discarded_stderr(self):
        wire = Wire(frame(1, b'{"status":"suc') + frame(2, b'synthetic-stderr')
                    + frame(1, b'ceeded","output":{}}'))
        channel = Attach(wire, 1, Clock())
        data = channel.exchange(b'{}')
        self.assertEqual({"status": "succeeded", "output": {}}, worker_response(data))
        self.assertNotIn(b"synthetic-stderr", data)
        self.assertEqual(b'{}', wire.sent)
        self.assertTrue(wire.half_closed)
        channel.close()
        self.assertTrue(wire.closed)
        with self.assertRaises(Rejected):
            channel.exchange(b'{}')

    def test_malformed_frames(self):
        for data in (b'\1', b'\1\0\0\0\0\0\0\5x', frame(0, b'x'),
                     b'\1\1\0\0\0\0\0\0', frame(3, b'x')):
            with self.subTest(data=data):
                with self.assertRaisesRegex(Rejected, "ATTACH_MALFORMED"):
                    Attach(Wire(data), 1, Clock()).exchange(b'{}')

    def test_stdout_and_stderr_and_frame_count_bounded(self):
        for data in (bytes([1, 0, 0, 0]) + (MAX_OUTPUT + 1).to_bytes(4, 'big'),
                     frame(1, b'x' * 40000) + frame(1, b'x' * 30000),
                     frame(2, b'x' * MAX_OUTPUT) * 3,
                     frame(2, b'') * 4097):
            with self.subTest(size=len(data)):
                with self.assertRaisesRegex(Rejected, "OUTPUT_TOO_LARGE"):
                    Attach(Wire(data), 1, Clock()).exchange(b'{}')

    def test_deadline_including_slow_fragmented_reads(self):
        clock = Clock()
        with self.assertRaisesRegex(Rejected, "TIMEOUT"):
            Attach(Wire(frame(1, b'{}'), clock, 0.05), 0.1, clock).exchange(b'{}')

    def test_connection_half_close_and_close_failure(self):
        for failure in ("send", "recv", "shutdown"):
            with self.subTest(failure=failure):
                with self.assertRaisesRegex(Rejected, "ATTACH_CONNECTION_FAILED"):
                    Attach(Wire(failure=failure), 1, Clock()).exchange(b'{}')
        with self.assertRaisesRegex(Rejected, "ATTACH_CLOSE_FAILED"):
            Attach(Wire(failure="close"), 1, Clock()).close()

    def test_input_bound_before_write(self):
        wire = Wire()
        with self.assertRaisesRegex(Rejected, "REQUEST_TOO_LARGE"):
            Attach(wire, 1, Clock()).exchange(b'x' * 65537)
        self.assertEqual(b'', wire.sent)

    def test_handshake_strict_upgrade(self):
        header = (b'HTTP/1.1 101 UPGRADED\r\nConnection: Upgrade\r\nUpgrade: tcp\r\n'
                  b'Content-Type: application/vnd.docker.raw-stream\r\n\r\n')
        wire = Wire(header + frame(1, b'{}'))
        channel = Attach(wire, 1, Clock())
        channel.handshake('/v1.45/containers/fixed/attach')
        self.assertEqual(b'{}', channel.exchange(b'{}'))
        for invalid in (header.replace(b'101 UPGRADED', b'200 OK'),
                        header.replace(b'Upgrade: tcp', b'Upgrade: other'),
                        header.replace(b'\r\n\r\n', b'\r\nContent-Length: 0\r\n\r\n'),
                        b'x' * 8193):
            with self.assertRaises(Rejected):
                Attach(Wire(invalid), 1, Clock()).handshake('/fixed')

    def test_documented_mime_forms_still_require_multiplex_frames(self):
        for mime in (b'application/vnd.docker.raw-stream',
                     b'application/vnd.docker.multiplexed-stream'):
            header = (b'HTTP/1.1 101 UPGRADED\r\nConnection: Upgrade\r\nUpgrade: tcp\r\n'
                      b'Content-Type: ' + mime + b'\r\n\r\n')
            with self.subTest(mime=mime):
                channel = Attach(Wire(header + frame(1, b'{}')), 1, Clock())
                channel.handshake('/fixed')
                self.assertEqual(b'{}', channel.exchange(b''))
                for body in (b'{"status":"failed"}', frame(0, b'{}'),
                             b'\1\1\0\0\0\0\0\0'):
                    channel = Attach(Wire(header + body), 1, Clock())
                    channel.handshake('/fixed')
                    with self.assertRaisesRegex(Rejected, 'ATTACH_MALFORMED'):
                        channel.exchange(b'')
        for mime in (b'application/json', b'application/octet-stream',
                     b'application/vnd.docker.multiplexed-stream; charset=utf-8'):
            header = (b'HTTP/1.1 101 UPGRADED\r\nConnection: Upgrade\r\nUpgrade: tcp\r\n'
                      b'Content-Type: ' + mime + b'\r\n\r\n')
            with self.subTest(mime=mime), self.assertRaisesRegex(Rejected, 'ATTACH_UPGRADE_FAILED'):
                Attach(Wire(header), 1, Clock()).handshake('/fixed')

    def test_stdout_only_strict_protocol(self):
        for data in (b'{} {}', b'{}', b'{"status":"failed","error":"synthetic-secret"}',
                     b'{"status":"succeeded","output":{},"extra":1}'):
            with self.assertRaises(Rejected):
                worker_response(data)


class LifecycleDocker(DockerAdapter):
    """Fixed API tested with in-memory transport; never creates a socket."""
    def __init__(self, record, raw, clock):
        super().__init__(Config('/run/easyshell-code/test/docker.sock', 'dedicated-id', IMAGE), clock)
        self.records = {record.name: replace(record, container_id=CID)}
        self.raw, self.calls, self.verified = raw, [], True

    def _identity(self):
        pass

    def _http(self, method, route, body=None, missing=False):
        self.calls.append((method, route))
        if route.endswith('/json'):
            if self.raw is None:
                raise NotFound()
            return copy.deepcopy(self.raw)
        if route.endswith('/start'):
            self.raw['State'].update(Running=True, Pid=99, Status='running')
            return None
        raise AssertionError('unexpected API')

    def _open_attach(self, route, deadline):
        self.calls.append(('ATTACH', route))
        return Attach(Wire(frame(1, b'{"status":"failed","error":"ISOLATION_UNVERIFIED"}')), deadline, self.clock)


class FixedApiTest(unittest.TestCase):
    def setUp(self):
        journal = Journal(':memory:')
        self.addCleanup(journal.close)
        record = journal.claim(sample())
        runtime = FakeRuntime()
        runtime.create(record.name, policy(IMAGE, record))
        self.adapter = LifecycleDocker(record, runtime.raw, Clock())

    def test_fixed_attach_then_start_and_refusal(self):
        channel = self.adapter.attach(CID, 1)
        self.adapter.start(CID, 1)
        result = worker_response(channel.exchange(b'{}'))
        channel.close()
        self.assertEqual('ISOLATION_UNVERIFIED', result['error'])
        self.assertIn(('POST', '/containers/' + CID + '/start'), self.adapter.calls)
        self.assertIn(('ATTACH', '/containers/' + CID
                       + '/attach?stream=1&stdin=1&stdout=1&stderr=1&logs=0'), self.adapter.calls)

    def test_unverified_policy_prevents_start_or_attach(self):
        del self.adapter.raw['HostConfig']['Memory']
        for operation in (self.adapter.start, self.adapter.attach):
            with self.assertRaisesRegex(Rejected, 'POLICY_UNVERIFIED'):
                operation(CID, 1)
        self.assertTrue(all(method == 'GET' for method, _ in self.adapter.calls))

    def test_expired_start_never_mutates(self):
        with self.assertRaisesRegex(Rejected, 'TIMEOUT'):
            self.adapter.start(CID, 0)
        self.assertEqual([], self.adapter.calls)

    def test_start_transport_failure_is_not_retried(self):
        original = self.adapter._http
        attempts = []

        def transport(method, route, body=None, missing=False):
            if route.endswith('/start'):
                attempts.append(route)
                raise ConnectionResetError()
            return original(method, route, body, missing)

        with patch.object(self.adapter, '_http', transport):
            with self.assertRaises(ConnectionResetError):
                self.adapter.start(CID, 1)
        self.assertEqual(1, len(attempts))

    def test_attach_transport_failure_is_not_retried(self):
        with patch.object(self.adapter, '_open_attach', side_effect=ConnectionResetError()) as transport:
            with self.assertRaises(ConnectionResetError):
                self.adapter.attach(CID, 1)
            self.assertEqual(1, transport.call_count)

    def test_bound_id_404_after_removal_is_observable(self):
        self.adapter.raw = None
        with self.assertRaises(NotFound):
            self.adapter.inspect(CID)
        self.assertEqual(('GET', '/containers/' + CID + '/json'), self.adapter.calls[-1])
