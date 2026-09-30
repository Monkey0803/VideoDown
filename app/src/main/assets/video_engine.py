"""Private JSON-lines bridge. No shell commands, credentials, or raw extractor logs in output."""
import os
import sys
import json
import hashlib
import subprocess
import threading
import time
import signal
from urllib.parse import urlparse
import re
import errno

def classify_failure(error, mode=None):
    """Return an actionable category without disclosing extractor messages or credentials."""
    message = str(error).lower()
    if mode == "extract_audio" and isinstance(error, subprocess.CalledProcessError):
        detail = error.stderr or b""
        if isinstance(detail, bytes):
            detail = detail[-8192:].decode("utf-8", errors="ignore")
        message += " " + detail.lower()
        if "no space" in message or "enospc" in message:
            return "SPACE"
        if any(value in message for value in ("permission denied", "read-only file system", "eacces", "erofs")):
            return "STORAGE_ACCESS"
        if "matches no streams" in message or "does not contain any stream" in message:
            return "NO_AUDIO"
        return "UNSUPPORTED"
    if "drm" in message:
        return "DRM"
    if "live streams" in message:
        return "LIVE"
    if "selected media changed" in message or "requested format" in message:
        return "CHANGED"
    if getattr(error, "errno", None) == errno.ENOSPC or "no space" in message or "enospc" in message:
        return "SPACE"
    if mode in ("version", "request") or isinstance(error, (subprocess.SubprocessError, ImportError)):
        return "RUNTIME"
    if getattr(error, "errno", None) in (errno.EACCES, errno.EPERM, errno.EROFS) or any(
            value in message for value in ("permission denied", "read-only file system", "eacces", "erofs")):
        return "STORAGE_ACCESS"
    if isinstance(error, FileNotFoundError):
        return "RUNTIME"
    if any(value in message for value in ("login", "sign in", "cookies", "captcha", "confirm you")):
        return "AUTH"
    status = re.search(r"\b(?:http(?: error)?|status(?: code)?)\s*[:=]?\s*(401|403|404|410|429)\b", message)
    if status:
        return {"401": "AUTH", "403": "ACCESS", "404": "EXPIRED", "410": "EXPIRED", "429": "RATE"}[status.group(1)]
    if any(value in message for value in ("timed out", "network", "resolve", "connection")):
        return "NETWORK"
    return "UNSUPPORTED"


os.setsid()
owner_pid = os.getppid()


def watch_owner():
    while os.getppid() == owner_pid:
        time.sleep(0.25)
    # If Android kills the app, stop the whole group, including a running merger.
    os.killpg(os.getpgrp(), signal.SIGKILL)


threading.Thread(target=watch_owner, daemon=True).start()
print(json.dumps({"event": "pid", "pid": os.getpid()}), flush=True)
sys.path.insert(0, sys.argv[1])
import yt_dlp


def emit(event, **data):
    print(json.dumps(dict(event=event, **data)), flush=True)


class QuietLogger:
    def debug(self, message):
        pass
    def warning(self, message):
        pass
    def error(self, message):
        pass


def public_info(info):
    keys = ("id", "title", "thumbnail", "duration", "uploader", "webpage_url", "extractor_key",
            "url", "ext", "format_id", "width", "height", "tbr", "filesize", "filesize_approx",
            "protocol", "vcodec", "acodec", "is_live", "live_status", "has_drm", "http_headers")
    result = {k: info[k] for k in keys if k in info}
    if info.get("formats"):
        result["formats"] = [public_info(f) for f in info["formats"]]
    if info.get("entries") is not None:
        result["entries"] = [public_info(e) for e in info["entries"] if e]
    return result


transfers = {}

def progress(data):
    transfers[data.get("filename", "media")] = (data.get("downloaded_bytes", 0), data.get("total_bytes") or data.get("total_bytes_estimate") or -1)
    values = list(transfers.values())
    emit("progress", downloaded=sum(v[0] for v in values),
         total=sum(v[1] for v in values) if all(v[1] > 0 for v in values) else -1,
         speed=data.get("speed") or 0)


def postprocess(data):
    if data.get("status") == "started":
        emit("phase", phase="MERGING")


def is_x_post(url):
    parsed = urlparse(url)
    return parsed.hostname in ("x.com", "www.x.com", "twitter.com", "www.twitter.com",
                               "m.x.com", "mobile.x.com", "m.twitter.com", "mobile.twitter.com") and bool(
        re.fullmatch(r"/(?:[A-Za-z0-9_]+/status|i/web/status|statuses)/\d{5,22}(?:/(?:video|photo)/[1-9]\d?)?/?",
                     parsed.path))


mode = "request"
try:
    request = json.loads(sys.stdin.readline())
    if not isinstance(request, dict) or request.get("mode") not in ("version", "parse", "download", "extract_audio"):
        raise ValueError("Invalid bridge request")
    mode = request["mode"]
    if request["mode"] == "version":
        from yt_dlp.version import __version__
        import yt_dlp_ejs
        subprocess.run([request["quickjs"], "-e", "console.log('js-ok')"], check=True, capture_output=True)
        subprocess.run([request["ffmpeg"], "-version"], check=True, capture_output=True)
        emit("version", version=__version__)
    elif request["mode"] == "extract_audio":
        emit("phase", phase="EXTRACTING")
        command = [request["ffmpeg"], "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                   "-i", request["input"], "-map", "0:a:0", "-vn"]
        # AAC sources can be copied losslessly into M4A; other codecs fall back to AAC.
        result = None
        if request.get("copy_audio"):
            result = subprocess.run(command + ["-c:a", "copy", "-movflags", "+faststart", request["output"]], capture_output=True)
        if result is None or result.returncode:
            if result is not None:
                failure = subprocess.CalledProcessError(result.returncode, command, stderr=result.stderr)
                if classify_failure(failure, "extract_audio") in ("SPACE", "STORAGE_ACCESS", "NO_AUDIO"):
                    raise failure
            if os.path.exists(request["output"]):
                os.remove(request["output"])
            subprocess.run(command + ["-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", request["output"]],
                           check=True, capture_output=True)
        emit("complete")
    else:
        options = {
            "quiet": True, "no_warnings": True, "logger": QuietLogger(),
            "noplaylist": not is_x_post(request["url"]),
            "playlistend": 20, "socket_timeout": 20, "retries": 2, "fragment_retries": 2,
            "extractor_retries": 1, "cachedir": False, "skip_unavailable_fragments": False,
            "ffmpeg_location": request["ffmpeg"],
            "js_runtimes": {"quickjs": {"path": request["quickjs"]}},
            "http_headers": request.get("headers", {}),
        }
        if request.get("cookies"):
            options["cookiefile"] = request["cookies"]
        if request["mode"] == "download":
            options.update({"outtmpl": request["output"], "format": request.get("format") or "bv*+ba/b",
                            "merge_output_format": "mp4/mkv", "continuedl": True,
                            "progress_hooks": [progress], "postprocessor_hooks": [postprocess]})
        if request.get("audio_only"):
            options["postprocessors"] = [{"key": "FFmpegExtractAudio", "preferredcodec": "m4a", "preferredquality": "0"}]
        with yt_dlp.YoutubeDL(options) as engine:
            info = engine.extract_info(request["url"], download=False)
            if info is None:
                raise ValueError("No media")
            if request["mode"] == "parse":
                emit("metadata", info=public_info(info))
            else:
                # Only one selected item is allowed per task, including after page changes.
                if info.get("entries") is not None:
                    target = request.get("media_id")
                    entries = [e for e in info["entries"] if e and str(e.get("id")) == target]
                    if len(entries) != 1:
                        raise ValueError("Selected media changed")
                    info = entries[0]
                if request.get("media_id") and str(info.get("id")) != request["media_id"]:
                    raise ValueError("Selected media changed")
                if info.get("is_live") or info.get("live_status") in ("is_live", "is_upcoming"):
                    raise ValueError("Live streams are not supported")
                if info.get("has_drm"):
                    raise ValueError("DRM protected")
                # Expired URLs or changed tracks must never append incompatible fragments.
                identity = {"id": info.get("id"), "format": request.get("format"),
                            "tracks": [{k: f.get(k) for k in ("url", "format_id", "width", "height", "vcodec", "acodec", "fragments")}
                                       for f in info.get("requested_formats", [info])]}
                digest = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
                directory = os.path.dirname(request["output"])
                marker = os.path.join(directory, ".selection")
                previous = open(marker).read() if os.path.exists(marker) else None
                if previous != digest:
                    for name in os.listdir(directory):
                        if name.startswith("media.") and os.path.isfile(os.path.join(directory, name)):
                            os.remove(os.path.join(directory, name))
                with open(marker, "w") as output:
                    output.write(digest)
                engine.process_ie_result(info, download=True)
                emit("complete")
except Exception as error:
    # The private protocol never returns URLs, cookie values, or raw extractor logs.
    kind = classify_failure(error, mode)
    emit("error", kind=kind)
    sys.exit(1)
