"""Checks the bridge's real HTML cleanup without starting its private process group."""
import ast
from html.parser import HTMLParser
from pathlib import Path
import re
import unittest
import json
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

source = Path(__file__).parents[2] / "main" / "assets" / "video_engine.py"
tree = ast.parse(source.read_text())
functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)
             and node.name == "strip_html_comments"]
namespace = {"HTMLParser": HTMLParser, "re": re}
exec(compile(ast.Module(body=functions, type_ignores=[]), str(source), "exec"), namespace)


class GenericHtmlTest(unittest.TestCase):
    def strip(self, html):
        return namespace["strip_html_comments"](html)

    def test_commented_sources_are_not_active_media(self):
        html = '<video><!--<source src="../old.webm">--><source src="https://cdn.test/real.webm"></video>'
        self.assertEqual('<video><source src="https://cdn.test/real.webm"></video>', self.strip(html))

    def test_script_and_style_payloads_are_preserved(self):
        html = '<script>const marker="<!--keep-->";const url="video.mp4";</script><style>p:before{content:"<!--keep-->"}</style>'
        self.assertEqual(html, self.strip(html))

    def test_unicode_crlf_offsets_preserve_all_other_content(self):
        html = '标题\r\n<!--old\r\nsource-->\r\n<video src="real.mp4">中文</video><!--tail-->'
        self.assertEqual('标题\r\n\r\n<video src="real.mp4">中文</video>', self.strip(html))

    @unittest.skipIf(sys.version_info < (3, 10), "The packaged yt-dlp requires Python 3.10 or newer")
    def test_packaged_extractor_uses_only_active_sources(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                body = b'<html><title>Comment regression</title><video><!--<source src="obsolete.mp4" type="video/mp4">--><source src="real.mp4" type="video/mp4"></video></html>'
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_args):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                request = dict(mode="parse", url=f"http://127.0.0.1:{server.server_port}/page",
                               ffmpeg=sys.executable, quickjs=sys.executable)
                result = subprocess.run([sys.executable, str(source), str(source.parent / "engine" / "yt-dlp")],
                                        input=json.dumps(request), text=True, capture_output=True, timeout=25)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                metadata = next(json.loads(line)["info"] for line in result.stdout.splitlines()
                                if json.loads(line).get("event") == "metadata")
                self.assertIn("real.mp4", json.dumps(metadata))
                self.assertNotIn("obsolete.mp4", json.dumps(metadata))
                self.assertEqual("HTML5MediaEmbed", metadata["extractor_key"])
            finally:
                server.shutdown()
                thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
