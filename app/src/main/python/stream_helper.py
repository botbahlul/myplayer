import os
import json
import importlib.util
import shutil
import subprocess

print("[DEBUG] Python:", __import__("sys").version)
print("[DEBUG] subprocess:", subprocess.__file__)
print("[DEBUG] os.pidfd_open exists:", hasattr(os, "pidfd_open"))

import yt_dlp
import streamlink
from streamlink.exceptions import StreamlinkError
from urllib.parse import urlparse

QUICKJS_PATH = None
YOUTUBE_COOKIE_FILE = "/data/data/com.app.myplayer/files/youtube_cookies.txt"
VIMEO_COOKIE_FILE = "/data/data/com.app.myplayer/files/vimeo_cookies.txt"
VIDIO_COOKIE_FILE = "/data/data/com.app.myplayer/files/vidio_cookies.txt"
print(f"[Cookies] Exists: {os.path.isfile(VIMEO_COOKIE_FILE)}")
print(f"[Cookies] Size: {os.path.getsize(VIMEO_COOKIE_FILE) if os.path.isfile(VIMEO_COOKIE_FILE) else 0}")

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
        "the web client only works when logged-in",
        "this video requires login",
        "you must be logged in",
    )

    return any(phrase in message for phrase in auth_phrases)


def _get_cookie_file(url):
    from urllib.parse import urlparse

    hostname = (urlparse(url).hostname or "").lower().rstrip(".")

    if not hostname:
        return None

    if hostname == "youtube.com" or hostname.endswith(".youtube.com") or hostname == "youtu.be":
        cookie_file = YOUTUBE_COOKIE_FILE
    elif hostname == "vimeo.com" or hostname.endswith(".vimeo.com"):
        cookie_file = VIMEO_COOKIE_FILE
    elif hostname == "vidio.com" or hostname.endswith(".vidio.com"):
        cookie_file = VIDIO_COOKIE_FILE
    else:
        safe_hostname = "".join(
            c if c.isalnum() or c in ".-_" else "_"
            for c in hostname
        )
        cookie_file = os.path.join(
            os.path.dirname(YOUTUBE_COOKIE_FILE),
            "cookies_" + safe_hostname + ".txt"
        )

    if os.path.isfile(cookie_file) and os.path.getsize(cookie_file) > 0:
        print("[Cookies] Using cookie file: " + cookie_file)
        return cookie_file

    print("[Cookies] No cookie file available for: " + hostname)
    return None


def _load_streamlink_cookies(session, url):
    cookie_file = _get_cookie_file(url)

    if not cookie_file:
        print("[Streamlink] No cookie file available")
        return

    try:
        import http.cookiejar
        from urllib.parse import urlparse

        cookie_jar = http.cookiejar.MozillaCookieJar(cookie_file)
        cookie_jar.load(ignore_discard=True, ignore_expires=False)

        hostname = (urlparse(url).hostname or "").lower()

        if hostname == "vidio.com" or hostname.endswith(".vidio.com"):
            print("[Streamlink] Skipping Vidio cookie")
            return

        now = __import__("time").time()
        relevant_cookies = []

        for cookie in cookie_jar:
            domain = cookie.domain.lstrip(".").lower()

            if hostname == domain or hostname.endswith("." + domain):
                expired = cookie.is_expired(now)
                relevant_cookies.append(
                    (cookie.name, cookie.domain, expired)
                )

        session.http.cookies.update(cookie_jar)

        print("[Streamlink] Cookie file loaded")
        print("[Streamlink] Relevant cookie count: " + str(len(relevant_cookies)))

        for name, domain, expired in relevant_cookies:
            print(
                "[Streamlink] Cookie name=" + name
                + ", domain=" + domain
                + ", expired=" + str(expired)
            )

    except Exception as e:
        print("[Streamlink] Failed to load cookies: " + repr(e))


def set_quickjs_path(path):
    global QUICKJS_PATH
    QUICKJS_PATH = path
    print("QuickJS path received from Java: " + QUICKJS_PATH)

print("yt-dlp version: " + yt_dlp.version.__version__)
print("yt-dlp-ejs available: " + str(importlib.util.find_spec("yt_dlp_ejs") is not None))
print("QuickJS path from PATH: " + str(shutil.which("qjs")))
print("QuickJS path from PATH: " + str(shutil.which("libqjs.so")))

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
        "format": "best[protocol^=m3u8]/best[protocol^=http]",
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

    cookie_file = _get_cookie_file(url)

    if cookie_file:
        options["cookiefile"] = cookie_file

    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            return ydl.extract_info(url, download=False)
    except yt_dlp.utils.DownloadError as e:
        if "requested format is not available" not in str(e).lower():
            raise
        print("[yt-dlp] No single-file format matched; retrying with a permissive selector")

    options["format"] = "bv*+ba/b"
    with yt_dlp.YoutubeDL(options) as ydl:
        return ydl.extract_info(url, download=False)

def _hdrs(fmt, info):
    return fmt.get("http_headers") or info.get("http_headers") or {}


def _build_choices(info):
    formats = info.get("formats") or []

    def has_v(f):
        return str(f.get("vcodec") or "none") != "none"

    def has_a(f):
        return str(f.get("acodec") or "none") != "none"

    def proto(f):
        return str(f.get("protocol") or "")

    # Audio-only progressive formats, used to pair with video-only formats
    audio_only = [
        f for f in formats
        if f.get("url") and not has_v(f) and has_a(f)
           and proto(f) in ("http", "https")
    ]
    best_audio = max(
        audio_only,
        key=lambda f: (f.get("ext") == "m4a", f.get("abr") or f.get("tbr") or 0),
        default=None,
    )

    choices = []
    for f in formats:
        if not f.get("url") or not has_v(f):
            continue

        p = proto(f)
        muxed = has_a(f)

        if p in ("http", "https"):
            pass
        elif p in ("m3u8", "m3u8_native") and muxed:
            pass
        else:
            continue  # skip DASH-segments, mhtml, video-only HLS, etc.

        if not muxed and best_audio is None:
            continue

        height = f.get("height") or 0
        fps = f.get("fps") or 0
        codec = str(f.get("vcodec")).split(".")[0]

        label = (str(height) + "p") if height else str(f.get("format_note") or f.get("format_id"))
        if fps and fps > 30:
            label += str(int(fps))
        label += " · " + codec + " · " + str(f.get("ext"))
        if p.startswith("m3u8"):
            label += " · HLS"

        choices.append({
            "id": str(f.get("format_id")),
            "label": label,
            "height": height,
            "fps": fps,
            "tbr": f.get("tbr") or 0,
            "video": {
                "url": f["url"],
                "headers": _hdrs(f, info),
                "hls": p.startswith("m3u8"),
            },
            "audio": None if muxed else {
                "url": best_audio["url"],
                "headers": _hdrs(best_audio, info),
                "hls": False,
            },
        })

    choices.sort(key=lambda c: (c["height"], c["fps"], c["tbr"]), reverse=True)
    return choices

def _pick_stream(info):
    url = info.get("url")
    if url:
        return url, info.get("http_headers") or {}

    # Tidak ada format tunggal: pakai master playlist HLS, biar ExoPlayer
    # yang menggabungkan video dan audio.
    formats = [f for f in (info.get("formats") or []) if f.get("url")]
    formats.sort(key=lambda f: (f.get("height") or 0, f.get("tbr") or 0), reverse=True)

    for f in formats:
        if str(f.get("protocol") or "").startswith("m3u8") and f.get("manifest_url"):
            return f["manifest_url"], f.get("http_headers") or info.get("http_headers") or {}

    return None, {}

def get_yt_stream_and_choices(url):
    try:
        info = _extract_info(url)
    except yt_dlp.utils.DownloadError as e:
        if _is_cookie_auth_error(e):
            return COOKIE_AUTH_REQUIRED + str(e)
        raise

    if not info:
        raise RuntimeError("yt-dlp: no video information found")

    '''
    stream_url = info.get("url")
    if not stream_url:
        raise RuntimeError("yt-dlp: no playable yt URL found")

    return json.dumps({
        "url": stream_url,
        "headers": info.get("http_headers") or {},
        "choices": _build_choices(info),
    })
    '''

    stream_url, headers = _pick_stream(info)
    if not stream_url:
        raise RuntimeError("yt-dlp: no playable yt URL found")

    return json.dumps({
        "url": stream_url,
        "headers": headers,
        "choices": _build_choices(info),
    })

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
    from urllib.parse import urlparse, urljoin
    import re

    session = streamlink.Streamlink()

    _load_streamlink_cookies(session, url)

    plugin_name, plugin_class, resolved_url = session.resolve_url(url)
    plugin = plugin_class(session, resolved_url)

    streams = plugin.streams()

    if not streams:
        raise RuntimeError("Streamlink: no stream found")

    hostname = (urlparse(url).hostname or "").lower()

    for name, stream in streams.items():
        print(
            "[Streamlink] stream="
            + str(name)
            + ", type="
            + stream.__class__.__name__
        )

    def inspect_mp4_video_codec(data):
        if not data:
            return "unknown"

        codecs = []

        # Parse sample descriptions inside MP4 initialization data.
        pos = 0

        while True:
            pos = data.find(b"stsd", pos)

            if pos < 0:
                break

            try:
                entry_count = int.from_bytes(
                    data[pos + 8:pos + 12],
                    byteorder="big"
                )

                entry_pos = pos + 12

                if entry_count > 64:
                    pos += 4
                    continue

                for _ in range(entry_count):
                    if entry_pos + 8 > len(data):
                        break

                    entry_size = int.from_bytes(
                        data[entry_pos:entry_pos + 4],
                        byteorder="big"
                    )

                    if entry_size < 8 or entry_pos + entry_size > len(data):
                        break

                    entry_type = data[
                        entry_pos + 4:entry_pos + 8
                    ].decode("ascii", errors="ignore")

                    if entry_type in ("avc1", "avc3"):
                        codecs.append("h264")

                    elif entry_type == "av01":
                        codecs.append("av1")

                    entry_pos += entry_size

            except Exception as e:
                print(
                    "[Vimeo codec] MP4 parsing error: "
                    + repr(e)
                )

            pos += 4

        if "h264" in codecs:
            return "h264"

        if "av1" in codecs:
            return "av1"

        return "unknown"

    def inspect_vimeo_stream(name, stream):
        response = None
        init_response = None

        try:
            playlist_url = stream.to_url()

            print(
                "[Vimeo codec] Inspecting stream: "
                + str(name)
            )

            response = session.http.get(
                playlist_url,
                timeout=15
            )

            if response.status_code != 200:
                print(
                    "[Vimeo codec] Playlist HTTP status: "
                    + str(response.status_code)
                )
                return "unknown"

            playlist = response.text

            if not playlist.lstrip().startswith("#EXTM3U"):
                print("[Vimeo codec] Not an HLS playlist")
                return "unknown"

            # Read the MP4 initialization segment declared by HLS.
            match = re.search(
                r'#EXT-X-MAP\s*:\s*URI="([^"]+)"',
                playlist
            )

            if not match:
                print(
                    "[Vimeo codec] No EXT-X-MAP initialization segment"
                )
                return "unknown"

            init_url = urljoin(playlist_url, match.group(1))

            init_response = session.http.get(
                init_url,
                timeout=15
            )

            if init_response.status_code != 200:
                print(
                    "[Vimeo codec] Init segment HTTP status: "
                    + str(init_response.status_code)
                )
                return "unknown"

            init_data = init_response.content

            if len(init_data) > 8 * 1024 * 1024:
                print(
                    "[Vimeo codec] Init segment unexpectedly large: "
                    + str(len(init_data))
                )
                return "unknown"

            codec = inspect_mp4_video_codec(init_data)

            print(
                "[Vimeo codec] Stream="
                + str(name)
                + ", video_codec="
                + codec
                + ", init_bytes="
                + str(len(init_data))
            )

            return codec

        except Exception as e:
            print(
                "[Vimeo codec] Inspection failed for "
                + str(name)
                + ": "
                + repr(e)
            )
            return "unknown"

        finally:
            if init_response is not None:
                init_response.close()

            if response is not None:
                response.close()

    # Vimeo: inspect available MP4 initialization segments and prefer H.264.
    if hostname == "vimeo.com" or hostname.endswith(".vimeo.com"):
        print("[Vimeo debug] Available streams:")

        for name, stream in streams.items():
            try:
                print(
                    "[Vimeo debug] name="
                    + str(name)
                    + ", type="
                    + stream.__class__.__name__
                    + ", url="
                    + str(stream.to_url())
                )
            except Exception as e:
                print(
                    "[Vimeo debug] name="
                    + str(name)
                    + ", URL unavailable: "
                    + repr(e)
                )

        preferred_streams = (
            "1080p",
            "1080p_alt",
            "720p",
            "720p_alt",
            "540p",
            "540p_alt",
            "480p",
            "480p_alt",
            "360p",
            "360p_alt",
            "240p",
            "240p_alt",
            "worst",
            "best",
        )

        ordered_names = [
            name for name in preferred_streams if name in streams
        ]

        if not ordered_names:
            raise RuntimeError(
                "Streamlink: no preferred Vimeo stream found"
            )



        codec_results = {}
        selected_name = None

        # Inspect streams and prefer H.264 for audio troubleshooting.
        for name in ordered_names:
            codec = inspect_vimeo_stream(name, streams[name])
            codec_results[name] = codec

            print(
                "[Vimeo codec] Inspected stream="
                + str(name)
                + ", codec="
                + codec
            )

            if codec == "h264":
                selected_name = name
                print(
                    "[Vimeo debug] Selecting H.264 stream: "
                    + str(selected_name)
                )
                break

        if selected_name is None:
            raise RuntimeError(
                "[Vimeo codec] No H.264 stream found. "
                "Refusing to force AV1 while troubleshooting audio. "
                "Inspected: "
                + str(codec_results)
            )

        print(
            "[Vimeo debug] Selected stream: "
            + str(selected_name)
        )


        selected_stream = streams[selected_name]

        try:
            fallback_url = selected_stream.to_manifest_url()

            print(
                "[Vimeo debug] Using manifest URL path: "
                + str(urlparse(fallback_url).path)
            )

        except (AttributeError, TypeError) as e:
            raise RuntimeError(
                "[Vimeo] Cannot obtain HLS manifest URL: "
                + repr(e)
            )


        print(
            "[Vimeo debug] Selected stream: "
            + str(selected_name)
        )

        print(
            "[Streamlink] Vimeo fallback URL path: "
            + str(urlparse(fallback_url).path)
        )

        return fallback_url

    # Other websites: use Streamlink's best stream.
    if "best" not in streams:
        raise RuntimeError("Streamlink: 'best' stream not found")

    best_stream = streams["best"]
    fallback_url = best_stream.to_url()

    print(
        "[Streamlink] Fallback URL path: "
        + str(urlparse(fallback_url).path)
    )

    # Only inspect the original master playlist for Vidio URLs.
    if hostname == "vidio.com" or hostname.endswith(".vidio.com"):
        try:
            stream_id = plugin.match["streamid"]
            master_url = plugin.api.get_stream_url(stream_id)

            print(
                "[Vidio master] API URL host: "
                + str(urlparse(master_url).hostname)
            )

            print(
                "[Vidio master] API URL path: "
                + str(urlparse(master_url).path)
            )

            response = session.http.get(master_url, timeout=15)

            try:
                print(
                    "[Vidio master] HTTP status: "
                    + str(response.status_code)
                )

                body = response.text

                is_hls = body.lstrip().startswith("#EXTM3U")
                has_variants = "#EXT-X-STREAM-INF" in body
                has_audio = "#EXT-X-MEDIA:TYPE=AUDIO" in body

                print("[Vidio master] Valid HLS: " + str(is_hls))
                print("[Vidio master] Has variants: " + str(has_variants))
                print("[Vidio master] Has audio rendition: " + str(has_audio))

                print(
                    "[Vidio master] Playlist preview: "
                    + body[:1500].replace("\n", " ").replace("\r", " ")
                )

                if is_hls and has_variants:
                    print(
                        "[Vidio master] Returning original master playlist"
                    )
                    return master_url

            finally:
                response.close()

            print("[Vidio master] URL is not a multivariant playlist")

        except Exception as e:
            print("[Vidio master] Failed: " + repr(e))

    print("[Streamlink] Returning video fallback URL")

    response = session.http.get(fallback_url, timeout=15)

    try:
        body = response.text
        print("[Vimeo manifest] HTTP status: " + str(response.status_code))
        print("[Vimeo manifest] Is HLS: " + str(body.lstrip().startswith("#EXTM3U")))
        print("[Vimeo manifest] Has variants: " + str("#EXT-X-STREAM-INF" in body))
        print("[Vimeo manifest] Has audio rendition: " + str("#EXT-X-MEDIA:TYPE=AUDIO" in body))
        print("[Vimeo manifest] Preview: " + body[:2000].replace("\n", " "))
    finally:
        response.close()

    return fallback_url


def resolve_stream_url(url):
    if not url or not url.strip():
        raise ValueError("URL is empty")

    ytdlp_error = None
    streamlink_error = None

    print("[Router] Resolving URL: " + url)

    # Backend utama: yt-dlp
    try:
        print("[Router] Trying yt-dlp first...")
        result = get_yt_url(url)

        if result and result.startswith(COOKIE_AUTH_REQUIRED):
            from urllib.parse import urlparse

            hostname = (urlparse(url).hostname or "").lower()

            if hostname == "youtube.com" or hostname.endswith(".youtube.com") or hostname == "youtu.be" or hostname.endswith(".youtu.be"):
                print("[Router] YouTube cookie authentication required")
                return result

            ytdlp_error = result
        elif result and result.strip() and result.strip().lower() != "none":
            print("[Router] yt-dlp succeeded")
            return result
        else:
            ytdlp_error = "yt-dlp returned an empty URL"

    except Exception as e:
        ytdlp_error = str(e) or e.__class__.__name__
        print("[Router] yt-dlp failed: " + ytdlp_error)

    # Backend cadangan: Streamlink
    try:
        print("[Router] Trying Streamlink fallback...")
        result = get_stream_url(url)

        if result and result.strip() and result.strip().lower() != "none":
            print("[Router] Streamlink succeeded")
            return result

        streamlink_error = "Streamlink returned an empty URL"

    except Exception as e:
        streamlink_error = str(e) or e.__class__.__name__
        print("[Router] Streamlink failed: " + streamlink_error)

    # Kedua backend gagal
    raise RuntimeError(
        "Both streaming backends failed.\n"
        "yt-dlp: " + str(ytdlp_error) + "\n"
        "Streamlink: " + str(streamlink_error)
    )


def is_streaming_url(url):
    streams = streamlink.streams(url)

    return bool(streams)


def get_yt_stream_data(url):
    try:
        info = _extract_info(url)
        for fmt in info.get("formats", []):
            print(
                "[yt-dlp format]"
                + " id=" + str(fmt.get("format_id"))
                + " protocol=" + str(fmt.get("protocol"))
                + " ext=" + str(fmt.get("ext"))
                + " vcodec=" + str(fmt.get("vcodec"))
                + " acodec=" + str(fmt.get("acodec"))
            )
    except yt_dlp.utils.DownloadError as e:
        if _is_cookie_auth_error(e):
            return COOKIE_AUTH_REQUIRED + str(e)
        raise

    if not info:
        raise RuntimeError("yt-dlp: no video information found")

    #stream_url = info.get("url")

    #if not stream_url:
    #    raise RuntimeError("yt-dlp: no playable stream URL found")

    #headers = info.get("http_headers") or {}

    stream_url, headers = _pick_stream(info)

    if not stream_url:
        raise RuntimeError("yt-dlp: no playable stream URL found")

    #return json.dumps({
    #    "url": stream_url,
    #    "headers": headers
    #})
    return json.dumps({
        "url": stream_url,
        "headers": headers,
        "choices": _build_choices(info)
    })


def resolve_stream_data(url):
    if not url or not url.strip():
        raise ValueError("URL is empty")

    ytdlp_error = None
    streamlink_error = None

    print("[Router] Resolving stream data: " + url)

    # Primary backend: yt-dlp
    try:
        print("[Router] Trying yt-dlp first...")

        result = get_yt_stream_data(url)

        if result and result.startswith(COOKIE_AUTH_REQUIRED):
            print("[Router] Cookie authentication required")
            return result

        if result:
            data = json.loads(result)

            if data.get("url"):
                data["resolver"] = "yt-dlp"
                data["choices"] = (data.get("choices") or []) + _streamlink_choices(url)
                print("[Router] yt-dlp succeeded")
                return json.dumps(data)

        ytdlp_error = "yt-dlp returned an empty URL"

    except Exception as e:
        error_text = str(e) or e.__class__.__name__
        print("[Router] yt-dlp failed: " + error_text)

        if _is_cookie_auth_error(error_text):
            return COOKIE_AUTH_REQUIRED + error_text

        ytdlp_error = error_text

    # Fallback backend: Streamlink
    from urllib.parse import urlparse

    try:
        print("[Router] Trying Streamlink fallback...")

        stream_url = get_stream_url(url)

        if stream_url and stream_url.strip():
            print("[Router] Streamlink succeeded")

            return json.dumps({
                "url": stream_url,
                "headers": {},
                "resolver": "streamlink",
                "choices": _streamlink_choices(url)
            })

        streamlink_error = "Streamlink returned an empty URL"

    except Exception as e:
        error_text = str(e) or e.__class__.__name__
        print("[Router] Streamlink failed: " + error_text)

        response = _find_response(e)
        if response is not None:
            print("[Streamlink error] HTTP status: " + str(response.status_code))
            print("[Streamlink error] server: " + str(response.headers.get("server")))
            print("[Streamlink error] Body: " + response.text[:300])
        else:
            print("[Streamlink error] No response object found")

        if _is_cookie_auth_error(error_text):
            return COOKIE_AUTH_REQUIRED + error_text

        streamlink_error = error_text

    raise RuntimeError(
        "Both streaming backends failed.\n"
        "yt-dlp: " + str(ytdlp_error) + "\n"
                                        "Streamlink: " + str(streamlink_error)
    )


def resolve_stream_data_bak(url):
    if not url or not url.strip():
        raise ValueError("URL is empty")

    ytdlp_error = None
    streamlink_error = None

    print("[Router] Resolving stream data: " + url)

    # Primary backend: yt-dlp
    try:
        print("[Router] Trying yt-dlp first...")

        result = get_yt_stream_data(url)


        if result.startswith(COOKIE_AUTH_REQUIRED):
            from urllib.parse import urlparse

            hostname = (urlparse(url).hostname or "").lower()

            is_youtube = (
                    hostname == "youtube.com"
                    or hostname.endswith(".youtube.com")
                    or hostname == "youtu.be"
                    or hostname.endswith(".youtu.be")
            )

            is_vimeo = (
                    hostname == "vimeo.com"
                    or hostname.endswith(".vimeo.com")
            )

            if is_youtube or is_vimeo:
                print(
                    "[Router] Cookie authentication required: "
                    + hostname
                )
                return result

            ytdlp_error = result

        else:
            data = json.loads(result)

            if data.get("url"):
                data["resolver"] = "yt-dlp"
                print("[Router] yt-dlp succeeded")
                return json.dumps(data)

            ytdlp_error = "yt-dlp returned an empty URL"

    except Exception as e:
        ytdlp_error = str(e) or e.__class__.__name__
        print("[Router] yt-dlp failed: " + ytdlp_error)

    # Fallback backend: Streamlink
    try:
        print("[Router] Trying Streamlink fallback...")

        stream_url = get_stream_url(url)

        if stream_url and stream_url.strip():
            print("[Router] Streamlink succeeded")

            return json.dumps({
                "url": stream_url,
                "headers": {},
                "resolver": "streamlink"
            })

        streamlink_error = "Streamlink returned an empty URL"

    except Exception as e:
        streamlink_error = str(e) or e.__class__.__name__
        print("[Router] Streamlink failed: " + streamlink_error)

    raise RuntimeError(
        "Both streaming backends failed.\n"
        "yt-dlp: " + str(ytdlp_error) + "\n"
        "Streamlink: " + str(streamlink_error)
    )

def _is_audio_only(fmt):
    if str(fmt.get("vcodec") or "none").lower() != "none":
        return False
    audio_ext = str(fmt.get("audio_ext") or "none").lower()
    fid = str(fmt.get("format_id") or "").lower()
    return audio_ext != "none" or "audio" in fid


def _cdn_of(fmt):
    # "hls-akfire_interconnect_quic-350" -> "akfire_interconnect_quic"
    parts = str(fmt.get("format_id") or "").split("-")
    return parts[1] if len(parts) > 2 else ""


def _streamlink_stream_url(stream):
    # Prioritaskan master manifest agar audio ikut terbawa
    try:
        return stream.to_manifest_url()
    except Exception:
        pass
    try:
        return stream.to_url()
    except Exception:
        return None


def _streamlink_choices(url):
    import re

    try:
        session = streamlink.Streamlink()
        _load_streamlink_cookies(session, url)
        plugin_name, plugin_class, resolved_url = session.resolve_url(url)
        streams = plugin_class(session, resolved_url).streams()
    except Exception as e:
        print("[Streamlink choices] Unavailable: " + repr(e))
        return []

    if not streams:
        return []

    aliases = ("best", "worst", "best-unfiltered", "worst-unfiltered")
    seen_urls = set()
    choices = []

    for name, stream in streams.items():
        if name in aliases:
            continue

        stream_url = _streamlink_stream_url(stream)
        if not stream_url:
            continue

        cls = stream.__class__.__name__
        is_hls = "HLS" in cls
        m = re.match(r"(\d+)", str(name))
        height = int(m.group(1)) if m else 0

        key = (stream_url, height)
        if key in seen_urls:
            continue
        seen_urls.add(key)

        choices.append({
            "id": "sl:" + str(name),
            "label": "Streamlink · " + str(name) + (" · HLS" if is_hls else ""),
            "height": height,
            "max_height": height if is_hls else 0,
            "fps": 0,
            "tbr": 0,
            "video": {"url": stream_url, "headers": {}, "hls": is_hls},
            "audio": None,
        })

    choices.sort(key=lambda c: c["height"], reverse=True)
    print("[Streamlink choices] Count: " + str(len(choices)))
    return choices


def _find_response(e):
    seen = set()
    stack = [e]
    while stack:
        x = stack.pop()
        if x is None or id(x) in seen:
            continue
        seen.add(id(x))
        r = getattr(x, "response", None)
        if r is not None:
            return r
        stack.extend([getattr(x, "err", None), x.__cause__, x.__context__])
    return None
