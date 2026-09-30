"""Exercises the actual bridge's pure classification function without initializing its process group."""
import ast
import re
import errno
import subprocess
import json
import io
from types import SimpleNamespace
import unittest
from pathlib import Path

source = Path(__file__).parents[2] / "main" / "assets" / "video_engine.py"
tree = ast.parse(source.read_text())
function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "classify_failure")
namespace = {"re": re, "errno": errno, "subprocess": subprocess}
exec(compile(ast.Module(body=[function], type_ignores=[]), str(source), "exec"), namespace)
classify = namespace["classify_failure"]


class EngineErrorClassificationTest(unittest.TestCase):
    def test_http_categories_are_distinct(self):
        for status, category in [(401, "AUTH"), (403, "ACCESS"), (404, "EXPIRED"), (410, "EXPIRED"), (429, "RATE")]:
            with self.subTest(status=status):
                self.assertEqual(category, classify(Exception(f"HTTP Error {status}: failed")))

    def test_explicit_login_and_storage_override_generic_http_denial(self):
        self.assertEqual("AUTH", classify(Exception("HTTP Error 403: Sign in to continue")))
        self.assertEqual("SPACE", classify(Exception("ENOSPC: no space left on device")))
        self.assertEqual("NETWORK", classify(Exception("connection timed out")))

    def test_format_protection_and_unknown_cases(self):
        self.assertEqual("CHANGED", classify(Exception("Selected media changed")))
        self.assertEqual("DRM", classify(Exception("DRM protected")))
        self.assertEqual("LIVE", classify(Exception("Live streams are not supported")))
        self.assertEqual("UNSUPPORTED", classify(Exception("Video id 403123 could not be extracted")))

    def test_storage_access_failures_keep_storage_recovery(self):
        for error in (PermissionError(errno.EACCES, "denied"), OSError(errno.EROFS, "read-only"),
                      Exception("unable to write: Permission denied")):
            self.assertEqual("STORAGE_ACCESS", classify(error, "download"))
        self.assertEqual("SPACE", classify(OSError(errno.ENOSPC, "full"), "download"))

    def test_engine_executable_and_version_failures_are_runtime(self):
        self.assertEqual("RUNTIME", classify(FileNotFoundError(errno.ENOENT, "missing binary"), "version"))
        self.assertEqual("RUNTIME", classify(PermissionError(errno.EACCES, "binary denied"), "version"))
        self.assertEqual("RUNTIME", classify(subprocess.CalledProcessError(1, ["ffmpeg"]), "version"))
        self.assertEqual("RUNTIME", classify(ImportError("missing engine module"), "version"))

    def test_local_ffmpeg_stderr_keeps_actionable_categories_without_details(self):
        cases = [(b"No space left on device /private/video", "SPACE"),
                 (b"Permission denied: /private/video", "STORAGE_ACCESS"),
                 (b"Stream map 0:a:0 matches no streams", "NO_AUDIO"),
                 (b"Unsupported output codec", "UNSUPPORTED")]
        for detail, expected in cases:
            with self.subTest(expected=expected):
                error = subprocess.CalledProcessError(1, ["ffmpeg"], stderr=detail)
                self.assertEqual(expected, classify(error, "extract_audio"))
                self.assertNotIn("private", classify(error, "extract_audio"))

    def test_malformed_requests_emit_structured_errors_without_traceback(self):
        request_block = next(node for node in tree.body if isinstance(node, ast.Try))
        for payload in ("", "not-json", "null", "[]", "{}", '{"mode":"invalid"}'):
            with self.subTest(payload=payload):
                events = []
                def emit(event, **data):
                    events.append(dict(event=event, **data))
                def stop(code):
                    raise SystemExit(code)
                environment = dict(namespace, json=json, mode="request", emit=emit,
                                   sys=SimpleNamespace(stdin=io.StringIO(payload), exit=stop))
                with self.assertRaises(SystemExit):
                    exec(compile(ast.Module(body=[request_block], type_ignores=[]), str(source), "exec"), environment)
                self.assertEqual([{"event": "error", "kind": "RUNTIME"}], events)

    def test_signed_urls_and_cookies_never_become_returned_details(self):
        details = "HTTP Error 403: https://example.test/video?signature=private-secret"
        self.assertEqual("ACCESS", classify(Exception(details)))
        self.assertNotIn("private-secret", classify(Exception(details)))


if __name__ == "__main__":
    unittest.main()
