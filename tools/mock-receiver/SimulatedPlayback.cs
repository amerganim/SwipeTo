using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using SwipeTo.Net.Receiver;
using SwipeTo.Protocol;

namespace SwipeTo.MockReceiver;

/// <summary>
/// Pretends to play: keeps a position clock instead of decoding. On load it fetches the start of the
/// media and a chunk from the middle with HTTP Range requests, which proves the sender's media server
/// is reachable and supports seeking.
/// </summary>
public sealed class SimulatedPlayback(HttpClient http) : IPlaybackTarget
{
    private readonly object _gate = new();
    private readonly Stopwatch _clock = new();
    private long _basePositionMs;
    private long? _durationMs;
    private bool _ended = true;

    public event Action? Changed;

    /// <summary>HTTP status of every Range request made, for tests and the console.</summary>
    public List<HttpStatusCode> RangeResponses { get; } = [];

    public MediaInfo? Current { get; private set; }

    public string? CurrentUrl => Current?.Url;

    /// <summary>URL prefetched (the first bytes already fetched), or null.</summary>
    public string? Prefetched { get; private set; }

    /// <summary>Handoffs whose media had been prefetched, for tests.</summary>
    public int PrefetchHits { get; private set; }

    public Transition? LastTransition { get; private set; }

    public void Prefetch(MediaInfo media, long positionMs)
    {
        if (Prefetched == media.Url) return;
        Prefetched = media.Url;
        Console.WriteLine($"[player] prefetching \"{media.Title}\" at {positionMs} ms");
        _ = FetchRangeAsync(media.Url, 0, 1023, CancellationToken.None).ContinueWith(_ => { }, TaskScheduler.Default);
    }

    public void DiscardPrefetch()
    {
        if (Prefetched is null) return;
        Prefetched = null;
        Console.WriteLine("[player] prefetch discarded");
    }

    public bool CanPlay(MediaInfo media) => media.Kind == MediaKind.Video || media.Kind == MediaKind.Image;

    public async Task LoadAsync(MediaInfo media, long positionMs, bool playing, Transition? transition, CancellationToken ct)
    {
        LastTransition = transition;
        if (Prefetched == media.Url)
        {
            PrefetchHits++;
            Prefetched = null;
        }
        else
        {
            Prefetched = null;
            var total = await FetchRangeAsync(media.Url, 0, 1023, ct);
            if (total is > 4096) await FetchRangeAsync(media.Url, total.Value / 2, total.Value / 2 + 1023, ct);
        }

        lock (_gate)
        {
            Current = media;
            _durationMs = media.DurationMs;
            _basePositionMs = positionMs;
            _ended = false;
            _clock.Reset();
            if (playing && media.Kind == MediaKind.Video) _clock.Start();
        }
        Console.WriteLine($"[player] loaded \"{media.Title}\" at {positionMs} ms, {(playing ? "playing" : "paused")}");
    }

    public void Play() => Update(() => { if (!_ended && Current?.Kind == MediaKind.Video) _clock.Start(); }, "play");

    public void Pause() => Update(() => _clock.Stop(), "pause");

    public void Seek(long positionMs) => Update(() =>
    {
        _basePositionMs = _durationMs is { } d ? Math.Min(positionMs, d) : positionMs;
        if (_clock.IsRunning) _clock.Restart(); else _clock.Reset();
    }, $"seek {positionMs}");

    public void Stop() => Update(() =>
    {
        _basePositionMs = PositionLocked();
        _clock.Reset();
        _ended = true;
    }, "stop");

    public PlaybackSnapshot Snapshot
    {
        get
        {
            lock (_gate)
            {
                var position = PositionLocked();
                if (!_ended && _durationMs is { } d && position >= d)
                {
                    _ended = true;
                    _basePositionMs = d;
                    _clock.Reset();
                }
                return new PlaybackSnapshot(position, _clock.IsRunning && !_ended, _ended);
            }
        }
    }

    private long PositionLocked()
    {
        var position = _basePositionMs + _clock.ElapsedMilliseconds;
        return _durationMs is { } d ? Math.Min(position, d) : position;
    }

    private void Update(Action change, string what)
    {
        lock (_gate)
        {
            if (Current is null) return;
            change();
        }
        Console.WriteLine($"[player] {what} -> {Snapshot.PositionMs} ms");
        Changed?.Invoke();
    }

    /// <summary>GETs one byte range; returns the full size from Content-Range.</summary>
    private async Task<long?> FetchRangeAsync(string url, long from, long to, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Range = new RangeHeaderValue(from, to);
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        lock (RangeResponses) RangeResponses.Add(response.StatusCode);
        if (!response.IsSuccessStatusCode) throw new HttpRequestException($"Media server answered {(int)response.StatusCode}");
        await response.Content.ReadAsByteArrayAsync(ct);
        Console.WriteLine($"[player] GET bytes={from}-{to} -> {(int)response.StatusCode}");
        return response.Content.Headers.ContentRange?.Length;
    }
}
