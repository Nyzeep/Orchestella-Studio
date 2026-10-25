"""Golden-vector tests for the independent AXP1/distcc client.

The client exists so the wire can be checked without trusting the C++ codec,
which means it has to be pinned to the same authority: the Java AXP1 fixture
and the captured distcc 3.4 request/response dumps.
"""

import json
import unittest
from pathlib import Path

import axp1_distcc_client as client

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
AXP1_GOLDEN = ROOT / "runtime/fastlane-distcc/src/test/resources/axp1/golden-v1.hex"
DISTCC_DIR = ROOT / "runtime/fastlane-distcc/src/test/resources/distcc"


def golden_frames():
    frames = {}
    for line in AXP1_GOLDEN.read_text(encoding="ascii").splitlines():
        name, _, body = line.partition(" ")
        frames[name] = bytes.fromhex("".join(body.split()))
    return frames


class FrameCodecTest(unittest.TestCase):
    def test_hello_matches_the_java_fixture_byte_for_byte(self):
        golden = golden_frames()
        token = "00112233" "44556677" "8899aabb" "ccddeeff"

        encoded = client.encode_frame(client.TYPE_HELLO, client.hello_payload(token))

        self.assertEqual(golden["HELLO"], encoded)

    def test_data_and_control_frames_match(self):
        golden = golden_frames()

        self.assertEqual(golden["DATA"], client.encode_frame(client.TYPE_DATA, b"hello"))
        self.assertEqual(golden["EOF"], client.encode_frame(client.TYPE_EOF, b""))
        self.assertEqual(golden["RST"], client.encode_frame(client.TYPE_RST, b"\x00\x02"))
        self.assertEqual(
            golden["PING"], client.encode_frame(client.TYPE_PING, bytes(range(1, 9)))
        )


class DistccStreamTest(unittest.TestCase):
    def test_request_matches_the_captured_dump(self):
        expected = bytes.fromhex(
            "".join((DISTCC_DIR / "request-v1.hex").read_text(encoding="ascii").split())
        )
        doti = b"int fastlane_answer(void) {\n    return 42; /* fixed distcc byte fixture */\n}\n"

        built = client.build_request(["cc", "-c", "fixture.c"], doti)

        self.assertEqual(expected, built)

    def test_response_dump_parses_into_five_packets(self):
        wire = bytes.fromhex(
            "".join((DISTCC_DIR / "response-v1.hex").read_text(encoding="ascii").split())
        )

        packets = client.decode_distcc_packets(wire)

        self.assertEqual(5, len(packets))
        self.assertEqual(0, int.from_bytes(packets["STAT"], "big"))
        self.assertEqual(b"warning: fixture only\n", packets["SERR"])
        self.assertEqual(b"fixture stdout\n", packets["SOUT"])
        self.assertEqual(15, len(packets["DOTO"]))
        self.assertTrue(packets["DOTO"].startswith(b"\x7fELF"))

    def test_truncated_response_is_rejected(self):
        wire = bytes.fromhex(
            "".join((DISTCC_DIR / "response-v1.hex").read_text(encoding="ascii").split())
        )

        with self.assertRaises(ValueError):
            client.decode_distcc_packets(wire[:40])


class CapabilityBlockTest(unittest.TestCase):
    def test_caps_block_declares_distcc_and_one_mebibyte(self):
        block = client.caps_block()

        self.assertEqual(2, int.from_bytes(block[:2], "big"))
        # id 1 (distcc) = 1
        self.assertEqual(1, block[2])
        self.assertEqual(1, int.from_bytes(block[3:5], "big"))
        self.assertEqual(1, block[5])
        # id 2 (max_frame) = 0x00100000
        self.assertEqual(2, block[6])
        self.assertEqual(4, int.from_bytes(block[7:9], "big"))
        self.assertEqual(1 << 20, int.from_bytes(block[9:13], "big"))


if __name__ == "__main__":
    unittest.main()
