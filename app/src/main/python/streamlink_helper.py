import streamlink


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
