import os
import yt_dlp
import importlib.util
import shutil
import subprocess
import streamlink

QUICKJS_PATH = None
COOKIE_FILE = "/data/data/com.app.myplayer/files/youtube_cookies.txt"


COOKIE_AUTH_REQUIRED = "COOKIE_AUTH_REQUIRED:"

def _is_cookie_auth_error(error):
    message = str(error).lower()

    auth_phrases = (
        "sign in to confirm you're not a bot",
        "sign in to confirm you’re not a bot",
        "cookies are no longer valid",
        "cookies have been rejected",
        "login required",
        "authentication required",
    )

    return any(phrase in message for phrase in auth_phrases)


def set_quickjs_path(path):
    global QUICKJS_PATH
    QUICKJS_PATH = path
    print("QuickJS path received from Java: " + QUICKJS_PATH)


print("yt-dlp version: " + yt_dlp.version.__version__)
print("yt-dlp-ejs available: " + str(importlib.util.find_spec("yt_dlp_ejs") is not None))
print("Deno path: " + str(shutil.which("deno")))
print("QuickJS path from PATH: " + str(shutil.which("qjs")))
print("Node.js path: " + str(shutil.which("node")))

def _extract_info(url):
    if not QUICKJS_PATH:
        raise RuntimeError("QuickJS path has not been configured by Java")

    qjs_path = QUICKJS_PATH

    print("QuickJS path: " + qjs_path)
    print("QuickJS file exists: " + str(os.path.isfile(qjs_path)))
    print("QuickJS executable: " + str(os.access(qjs_path, os.X_OK)))

    if not os.path.isfile(qjs_path):
        raise RuntimeError("QuickJS file not found: " + qjs_path)

    if not os.access(qjs_path, os.X_OK):
        raise RuntimeError("QuickJS is not executable: " + qjs_path)

    try:
        result = subprocess.run(
            [qjs_path, "-e", "print(123)"],
            capture_output=True,
            text=True,
            timeout=10
        )

        print("QuickJS return code: " + str(result.returncode))
        print("QuickJS stdout: " + result.stdout.strip())
        print("QuickJS stderr: " + result.stderr.strip())

        if result.returncode != 0:
            raise RuntimeError(
                "QuickJS test failed with exit code "
                + str(result.returncode)
                + ": "
                + result.stderr.strip()
            )

    except Exception as e:
        print("QuickJS execution failed: " + repr(e))
        raise

    options = {
        "format": "best",
        "quiet": False,
        "no_warnings": False,
        "noplaylist": True,
        "js_runtimes": {
            "quickjs": {
                "path": qjs_path
            }
        },
        "verbose": True,
    }

    if os.path.isfile(COOKIE_FILE):
        options["cookiefile"] = COOKIE_FILE
        print("YouTube cookies enabled: " + COOKIE_FILE)
    else:
        print("YouTube cookies file not found: " + COOKIE_FILE)

    with yt_dlp.YoutubeDL(options) as ydl:
        return ydl.extract_info(url, download=False)



def get_yt_url(url):
    try:
        info = _extract_info(url)
    except yt_dlp.utils.DownloadError as e:
        if _is_cookie_auth_error(e):
            print("YouTube cookie authentication may be invalid: " + str(e))
            return COOKIE_AUTH_REQUIRED + str(e)
        raise

    if not info:
        raise RuntimeError("yt-dlp: no video information found")

    yt_url = info.get("url")

    if not yt_url:
        raise RuntimeError("yt-dlp: no playable yt URL found")

    return yt_url


def is_yt_url(url):
    if not url:
        return False

    try:
        return bool(_extract_info(url))
    except Exception as e:
        print("YouTube URL check failed: " + str(e))
        return False

def get_stream_url(url):
    streams = streamlink.streams(url)

    if not streams:
        raise RuntimeError("Streamlink: no stream found")

    if "best" not in streams:
        raise RuntimeError("Streamlink: 'best' stream not found")

    return streams["best"].to_url()


def is_streaming_url(url):
    streams = streamlink.streams(url)

    return bool(streams)
