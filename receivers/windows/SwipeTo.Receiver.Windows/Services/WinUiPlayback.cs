using System.Diagnostics;
using Microsoft.Extensions.Logging;
using Microsoft.UI.Dispatching;
using SwipeTo.Net.Receiver;
using SwipeTo.Protocol;
using SwipeTo.Receiver.Windows.Ui;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace SwipeTo.Receiver.Windows.Services;

/// <summary>
/// Plays handed-off media in the full-screen <see cref="PlayerWindow"/> with the Windows media
/// pipeline. Window work runs on the UI thread; <see cref="MediaPlayer"/> itself is free-threaded.
/// </summary>
internal sealed class WinUiPlayback(DispatcherQueue ui, string dataDir, YouTubeLookup youTube, ILogger log) : IPlaybackTarget
{
    /// <summary>App content resolved before handoff_ack: media key to YouTube video id.</summary>
    private readonly Dictionary<string, string> _resolved = new(StringComparer.Ordinal);

    /// <summary>YouTube's player has the current media (instead of a MediaPlayer or a photo).</summary>
    private bool _app;
    private YouTubeStatus _yt;
    private readonly Stopwatch _ytSince = new();
    private Stopwatch? _ytFirstFrame;
    private string? _ytVideo;

    private static readonly TimeSpan OpenTimeout = TimeSpan.FromSeconds(15);

    /// <summary>A playing prefetch this close to the phone's position is moved only if it drifts further.</summary>
    private const long PrefetchDriftMs = 500;

    /// <summary>A playing prefetch this close to the handoff position is shown without a seek.</summary>
    private const long WarmToleranceMs = 400;
    private static readonly HashSet<string> ImageTypes = ["image/jpeg", "image/png", "image/gif", "image/bmp", "image/webp", "image/tiff"];
    /// <summary>
    /// For the phone's media server. Fails fast so <see cref="HttpRangeStream"/> retries soon: after a
    /// Wi-Fi blip on the A15 a request on a pooled connection that died with it hung for the whole
    /// timeout, freezing the picture after the phone was back. Blocks are 512 KiB; even at 1 MB/s
    /// that is half a second.
    /// </summary>
    private static readonly HttpClient Http = new(new SocketsHttpHandler
    {
        ConnectTimeout = TimeSpan.FromSeconds(3),
        PooledConnectionIdleTimeout = TimeSpan.FromSeconds(5),
    })
    {
        Timeout = TimeSpan.FromSeconds(6),
    };

    private readonly object _gate = new();

    private PlayerWindow? _window;
    private MediaPlayer? _player;
    private HttpRangeStream? _stream;
    private string? _currentUrl;
    private PrefetchedVideo? _prefetch;
    private bool _wantPlaying;
    private bool _ended = true;
    private long _lastPositionMs;

    public event Action? Changed;

    /// <summary>
    /// UI thread. Creates the player window ahead of time and, in the background, plays a tiny clip
    /// muted so the media pipeline and H.264 decoder are loaded before the first handoff (a cold
    /// start costs about a second).
    /// </summary>
    public void WarmUp()
    {
        ui.TryEnqueue(async () =>
        {
            try
            {
                var clock = Stopwatch.StartNew();
                await EnsureWindow().YouTube.WarmUpAsync();
                log.LogInformation("YouTube player warmed up in {Ms} ms", clock.ElapsedMilliseconds);
            }
            catch (Exception e)
            {
                log.LogWarning("YouTube player warm-up failed (first YouTube handoff will be slower): {Error}", e.Message);
            }
        });
        EnsureWindow();
        _ = Task.Run(WarmUpPipelineAsync);
    }

    private async Task WarmUpPipelineAsync()
    {
        try
        {
            var clock = Stopwatch.StartNew();
            var clip = await WarmUpClip.GetAsync(dataDir);
            using var player = new MediaPlayer { AutoPlay = false, IsMuted = true };
            var playing = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            player.PlaybackSession.PlaybackStateChanged += (s, _) =>
            {
                if (s.PlaybackState == MediaPlaybackState.Playing) playing.TrySetResult();
            };
            player.MediaFailed += (_, e) => playing.TrySetException(new InvalidOperationException(e.ErrorMessage));
            player.Source = MediaSource.CreateFromUri(new Uri(clip));
            player.Play();
            await playing.Task.WaitAsync(TimeSpan.FromSeconds(10));
            log.LogInformation("Media pipeline warmed up in {Ms} ms", clock.ElapsedMilliseconds);
        }
        catch (Exception e)
        {
            log.LogWarning("Media warm-up failed (first handoff will be slower): {Error}", e.Message);
        }
    }

    public bool CanPlay(MediaInfo media) => media.Kind switch
    {
        MediaKind.Video => media.Mime.StartsWith("video/", StringComparison.Ordinal),
        MediaKind.Image => ImageTypes.Contains(media.Mime),
        // With its id YouTube needs nothing else; by title and channel it needs the user's API key.
        MediaKind.App => media.Service == "youtube" && (media.ContentId is not null || youTube.HasKey),
        _ => false,
    };

    public async Task<bool> ResolveAppAsync(MediaInfo media, CancellationToken ct)
    {
        if (media.Service != "youtube") return false;
        var id = media.ContentId ?? await youTube.FindAsync(media.Title, media.Channel, ct);
        if (id is null) return false;
        lock (_resolved) _resolved[media.Key] = id;
        return true;
    }

    public string? CurrentUrl
    {
        get { lock (_gate) return _ended ? null : _currentUrl; }
    }

    /// <summary>
    /// Opens a video during the aim, paused and unseen (docs/protocol.md, 5.3), so the handoff that
    /// follows the swipe adopts an already-open player. Photos load fast enough not to need it.
    /// </summary>
    /// <summary>
    /// Opens the video hidden and muted and keeps it <em>playing</em> in step with the phone, so a swipe
    /// only has to show it. (Held paused, it fell behind while the arm was up and the handoff had to seek
    /// and fetch again over Wi-Fi: 800 ms on the A15.) Each repeat moves it only if it drifted.
    /// </summary>
    public void Prefetch(MediaInfo media, long positionMs)
    {
        if (media.Kind != MediaKind.Video) return;
        lock (_gate)
        {
            if (_prefetch?.Url == media.Url)
            {
                _prefetch.Update(positionMs);
                if (_prefetch.Ready.IsCompletedSuccessfully)
                {
                    var session = _prefetch.Ready.Result.Player.PlaybackSession;
                    var drift = (long)session.Position.TotalMilliseconds - positionMs;
                    if (Math.Abs(drift) > PrefetchDriftMs) session.Position = TimeSpan.FromMilliseconds(positionMs);
                }
                return;
            }
            Release(_prefetch);
            var cancel = new CancellationTokenSource();
            var prefetch = new PrefetchedVideo(media.Url, cancel);
            prefetch.Update(positionMs);
            prefetch.Ready = OpenVideoAsync(media, prefetch.PositionNow, cancel.Token);
            _prefetch = prefetch;
        }
        log.LogInformation("Prefetching video at {Position} ms", positionMs);
    }

    public void DiscardPrefetch()
    {
        PrefetchedVideo? old;
        lock (_gate)
        {
            old = _prefetch;
            _prefetch = null;
        }
        if (old is null) return;
        Release(old);
        log.LogInformation("Prefetch discarded");
    }

    public async Task LoadAsync(MediaInfo media, long positionMs, bool playing, Transition? transition, CancellationToken ct)
    {
        var clock = Stopwatch.StartNew();

        if (media.IsApp)
        {
            await LoadYouTubeAsync(media, positionMs, playing);
            log.LogInformation("YouTube player up after {Ms} ms at {Position} ms", clock.ElapsedMilliseconds, positionMs);
            return;
        }

        if (media.Kind == MediaKind.Image)
        {
            DiscardPrefetch();
            await ui.RunAsync(() =>
            {
                ReleasePlayer();
                EnsureWindow().ShowPhoto(new Uri(media.Url), media.Title, transition);
                lock (_gate)
                {
                    _ended = false;
                    _wantPlaying = false;
                    _lastPositionMs = positionMs;
                    _currentUrl = media.Url;
                }
            });
            log.LogInformation("Photo shown after {Ms} ms{Transition}", clock.ElapsedMilliseconds, transition is { } t ? $" ({t})" : "");
            return;
        }

        // Adopt the player opened during the aim, if it is this video and it opened.
        (MediaPlayer Player, HttpRangeStream Stream)? ready = null;
        if (TakePrefetch(media.Url) is { } pre)
        {
            try
            {
                ready = await pre.Ready.WaitAsync(OpenTimeout, ct);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                log.LogWarning("Prefetched video unusable, opening again: {Error}", e.Message);
            }
        }
        var prefetched = ready is not null;

        HttpRangeStream stream;
        MediaPlayer player;
        Task opened;
        var warm = false;
        if (ready is { } r)
        {
            (player, stream) = r;
            opened = Task.CompletedTask;
            var session0 = player.PlaybackSession;
            var drift = (long)session0.Position.TotalMilliseconds - positionMs;
            // Already playing close to where the phone was: show it as it is, no seek, no new fetch.
            warm = playing && session0.PlaybackState == MediaPlaybackState.Playing && Math.Abs(drift) <= WarmToleranceMs;
            log.LogInformation("Prefetched video is {State} at {Drift:+0;-0} ms from the handoff position", session0.PlaybackState, drift);
            player.IsMuted = false;
            if (!warm) player.Pause();
        }
        else
        {
            // Feed the player a range-reading stream rather than the URL: the built-in HTTP source buffers
            // about two seconds before it starts, this starts as soon as the first frames have arrived.
            stream = await HttpRangeStream.OpenAsync(Http, new Uri(media.Url), ct: ct);
            player = new MediaPlayer { AutoPlay = false, AudioCategory = MediaPlayerAudioCategory.Movie };
            var openedSource = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            player.MediaOpened += (_, _) => openedSource.TrySetResult();
            player.MediaFailed += (_, e) => openedSource.TrySetException(new InvalidOperationException($"{e.Error}: {e.ErrorMessage}"));
            player.Source = MediaSource.CreateFromStream(stream.AsRandomAccessStream(), media.Mime);
            opened = openedSource.Task;
        }

        // Go full screen (black) while the media opens, rather than one after the other.
        await ui.RunAsync(() =>
        {
            ReleasePlayer();
            EnsureWindow().ShowVideo(player, media.Title);
        });
        var shownMs = clock.ElapsedMilliseconds;

        try
        {
            await opened.WaitAsync(OpenTimeout, ct);
        }
        catch
        {
            await ui.RunAsync(() => _window?.HideAndReset());
            player.Dispose();
            await stream.DisposeAsync();
            throw;
        }
        var openedMs = clock.ElapsedMilliseconds;

        var session = player.PlaybackSession;
        if (!warm)
        {
            // The phone keeps playing until we show frames (docs/protocol.md, 5.4): catch up by the load time.
            var catchUp = playing ? clock.ElapsedMilliseconds : 0;
            positionMs = media.DurationMs is { } d ? Math.Min(positionMs + catchUp, d) : positionMs + catchUp;
            session.Position = TimeSpan.FromMilliseconds(positionMs);
        }
        lock (_gate)
        {
            _player = player;
            _stream = stream;
            _ended = false;
            _wantPlaying = playing;
            _lastPositionMs = positionMs;
            _currentUrl = media.Url;
        }
        // The video stays hidden until the first seek lands (or it plays), so frame 0 never flashes up.
        var revealed = 0;
        void Reveal()
        {
            if (Interlocked.Exchange(ref revealed, 1) == 0) ui.TryEnqueue(() => _window?.RevealVideo());
        }
        session.PlaybackStateChanged += (s, _) =>
        {
            if (s.PlaybackState == MediaPlaybackState.Playing && clock.IsRunning)
            {
                clock.Stop();
                log.LogInformation("First frame playing {Ms} ms after handoff at {Position} ms, {How} ({KiB} KiB in {Requests} range requests)",
                    clock.ElapsedMilliseconds, (long)s.Position.TotalMilliseconds, prefetched ? "PREFETCHED" : "cold", stream.BytesFetched / 1024, stream.Requests);
            }
            if (!IsCurrent(player)) return;
            if (s.PlaybackState == MediaPlaybackState.Playing) Reveal();
            RaiseChanged();
        };
        session.SeekCompleted += (_, _) =>
        {
            if (!IsCurrent(player)) return;
            Reveal();
            RaiseChanged();
        };
        player.MediaEnded += (_, _) =>
        {
            if (!IsCurrent(player)) return;
            lock (_gate)
            {
                _ended = true;
                _wantPlaying = false;
            }
            RaiseChanged();
        };
        player.MediaFailed += (_, e) =>
        {
            log.LogWarning("Playback failed: {Error}", e.ErrorMessage);
            if (IsCurrent(player)) ui.TryEnqueue(() => EndPlayback("after a playback error"));
        };

        if (warm)
        {
            // No state change or seek is coming to reveal it: it is playing already.
            await ui.RunAsync(() => _window?.RevealVideo());
            Interlocked.Exchange(ref revealed, 1);
            clock.Stop();
            log.LogInformation("First frame playing {Ms} ms after handoff at {Position} ms, PREFETCHED warm ({KiB} KiB in {Requests} range requests)",
                clock.ElapsedMilliseconds, (long)session.Position.TotalMilliseconds, stream.BytesFetched / 1024, stream.Requests);
            RaiseChanged();
        }
        else if (playing)
        {
            player.Play();
        }
        log.LogInformation("Video window up after {Shown} ms, media opened after {Opened} ms, at {Position} ms", shownMs, openedMs, positionMs);
    }

    /// <summary>App content: YouTube's own player shows it; we only say which video, where and whether to play.</summary>
    private async Task LoadYouTubeAsync(MediaInfo media, long positionMs, bool playing)
    {
        string? id;
        lock (_resolved) _resolved.TryGetValue(media.Key, out id);
        id ??= media.ContentId ?? throw new InvalidOperationException("YouTube content was not resolved before the handoff");
        DiscardPrefetch();
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        ui.TryEnqueue(async () =>
        {
            try
            {
                ReleasePlayer();
                var window = EnsureWindow();
                window.ShowYouTube(media.Title);
                lock (_gate)
                {
                    _app = true;
                    _ytVideo = id;
                    _yt = new YouTubeStatus(positionMs, YouTubeStatus.Unstarted, null);
                    _ytSince.Restart();
                    _ytFirstFrame = Stopwatch.StartNew();
                    _ended = false;
                    _wantPlaying = playing;
                    _lastPositionMs = positionMs;
                    _currentUrl = media.Key;
                }
                await window.YouTube.LoadAsync(id, positionMs, playing);
                done.SetResult();
            }
            catch (Exception e)
            {
                done.TrySetException(e);
            }
        });
        await done.Task;
    }

    /// <summary>UI thread: a report from YouTube's player.</summary>
    private void OnYouTubeStatus(YouTubeStatus status)
    {
        bool changed;
        lock (_gate)
        {
            if (!_app) return;
            changed = status.State != _yt.State;
            _yt = status;
            _ytSince.Restart();
            _lastPositionMs = status.PositionMs;
        }
        if (status.State == YouTubeStatus.Playing && _ytFirstFrame is { } first)
        {
            _ytFirstFrame = null;
            log.LogInformation("First frame playing {Ms} ms after handoff at {Position} ms, YouTube {Video}", first.ElapsedMilliseconds, status.PositionMs, _ytVideo);
        }
        if (status.Error is { } error)
        {
            // 101/150: the owner does not allow embedding; open it on YouTube's site instead, at the same second.
            log.LogWarning("YouTube player error {Error} for {Video}", error, _ytVideo);
            if (error is 101 or 150 && _ytVideo is { } video)
            {
                var url = $"https://www.youtube.com/watch?v={video}&t={_lastPositionMs / 1000}s";
                Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            }
            EndPlayback("after a YouTube error");
            return;
        }
        if (status.State == YouTubeStatus.Ended)
        {
            lock (_gate)
            {
                _ended = true;
                _wantPlaying = false;
            }
        }
        if (changed) RaiseChanged();
    }

    public void Play()
    {
        if (IsApp(() => _ = _window!.YouTube.PlayAsync(), wantPlaying: true)) return;
        lock (_gate)
        {
            if (_player is null || _ended) return;
            _wantPlaying = true;
            _player.Play();
        }
        RaiseChanged();
    }

    public void Pause()
    {
        if (IsApp(() => _ = _window!.YouTube.PauseAsync(), wantPlaying: false)) return;
        lock (_gate)
        {
            if (_player is null) return;
            _wantPlaying = false;
            _player.Pause();
        }
        RaiseChanged();
    }

    public void Seek(long positionMs)
    {
        if (IsApp(() => _ = _window!.YouTube.SeekAsync(positionMs), position: positionMs)) return;
        lock (_gate)
        {
            if (_player is null) return;
            _player.PlaybackSession.Position = TimeSpan.FromMilliseconds(positionMs);
        }
        // SeekCompleted raises Changed once the new position is real.
    }

    public void Stop() => ui.TryEnqueue(() => EndPlayback("by the phone"));

    public void ShowNotice(string text) => ui.TryEnqueue(() =>
    {
        if (_window?.IsShown == true) _window.ShowNotice(text);
    });

    public void HideNotice() => ui.TryEnqueue(() => _window?.HideNotice());

    /// <summary>For app content: runs <paramref name="command"/> on YouTube's player (UI thread) and notes the intent.</summary>
    private bool IsApp(Action command, bool? wantPlaying = null, long? position = null)
    {
        lock (_gate)
        {
            if (!_app || _ended) return _app;
            if (wantPlaying is { } want) _wantPlaying = want;
            if (position is { } p)
            {
                _yt = _yt with { PositionMs = p };
                _ytSince.Restart();
            }
        }
        ui.TryEnqueue(() => command());
        RaiseChanged();
        return true;
    }

    public PlaybackSnapshot Snapshot
    {
        get
        {
            lock (_gate)
            {
                if (_app)
                {
                    var playingNow = _yt.State == YouTubeStatus.Playing;
                    var position = _yt.PositionMs + (playingNow ? _ytSince.ElapsedMilliseconds : 0);
                    _lastPositionMs = position;
                    var want = _wantPlaying && !_ended;
                    return new PlaybackSnapshot(position, want, _ended, Buffering: want && !playingNow);
                }
                var showing = false;
                if (_player is not null)
                {
                    try
                    {
                        _lastPositionMs = (long)_player.PlaybackSession.Position.TotalMilliseconds;
                        showing = _player.PlaybackSession.PlaybackState == MediaPlaybackState.Playing;
                    }
                    catch (ObjectDisposedException)
                    {
                    }
                }
                // Report intent while buffering so the phone's play button does not flicker, and say
                // that it is buffering: the phone keeps playing until frames show.
                var wantPlaying = _wantPlaying && !_ended;
                return new PlaybackSnapshot(_lastPositionMs, wantPlaying, _ended, Buffering: wantPlaying && !showing);
            }
        }
    }

    private bool IsCurrent(MediaPlayer player)
    {
        lock (_gate) return _player == player;
    }

    private PlayerWindow EnsureWindow()
    {
        if (_window is not null) return _window;
        _window = new PlayerWindow(dataDir);
        _window.ViewerClosed += () => EndPlayback("by the viewer");
        _window.YouTube.Status += OnYouTubeStatus;
        return _window;
    }

    /// <summary>UI thread. Esc, close button, <see cref="Stop"/> or a failure: playback ends and the phone hears about it.</summary>
    private void EndPlayback(string by)
    {
        if (_window is null || !_window.IsShown) return;
        _ = Snapshot; // capture the final position before the player goes
        ReleasePlayer();
        _window.HideAndReset();
        lock (_gate)
        {
            _ended = true;
            _wantPlaying = false;
        }
        log.LogInformation("Player closed {By} at {Position} ms", by, _lastPositionMs);
        RaiseChanged();
    }

    private PrefetchedVideo? TakePrefetch(string url)
    {
        PrefetchedVideo? taken;
        lock (_gate)
        {
            taken = _prefetch;
            _prefetch = null;
        }
        if (taken is null || taken.Url == url) return taken;
        Release(taken);
        return null;
    }

    /// <summary>Opens a video paused at a position, without showing it.</summary>
    private static async Task<(MediaPlayer Player, HttpRangeStream Stream)> OpenVideoAsync(MediaInfo media, Func<long> positionNow, CancellationToken ct)
    {
        var stream = await HttpRangeStream.OpenAsync(Http, new Uri(media.Url), ct: ct);
        var player = new MediaPlayer { AutoPlay = false, AudioCategory = MediaPlayerAudioCategory.Movie };
        var opened = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        player.MediaOpened += (_, _) => opened.TrySetResult();
        player.MediaFailed += (_, e) => opened.TrySetException(new InvalidOperationException($"{e.Error}: {e.ErrorMessage}"));
        player.Source = MediaSource.CreateFromStream(stream.AsRandomAccessStream(), media.Mime);
        try
        {
            await opened.Task.WaitAsync(OpenTimeout, ct);
        }
        catch
        {
            player.Dispose();
            await stream.DisposeAsync();
            throw;
        }
        // Where the phone is by now (opening took a while), muted and unseen until the handoff.
        player.IsMuted = true;
        player.PlaybackSession.Position = TimeSpan.FromMilliseconds(positionNow());
        player.Play();
        return (player, stream);
    }

    private static void Release(PrefetchedVideo? prefetch)
    {
        if (prefetch is null) return;
        prefetch.Cancel.Cancel();
        prefetch.Ready.ContinueWith(t =>
        {
            if (!t.IsCompletedSuccessfully) return;
            t.Result.Player.Dispose();
            t.Result.Stream.Dispose();
        }, TaskScheduler.Default);
    }

    private sealed class PrefetchedVideo(string url, CancellationTokenSource cancel)
    {
        private readonly Stopwatch _since = new();
        private long _positionMs;

        public string Url { get; } = url;
        public CancellationTokenSource Cancel { get; } = cancel;
        public Task<(MediaPlayer Player, HttpRangeStream Stream)> Ready { get; set; } = null!;

        /// <summary>The phone's position from its latest aim_start.</summary>
        public void Update(long positionMs)
        {
            _positionMs = positionMs;
            _since.Restart();
        }

        /// <summary>Where the phone should be now, assuming it kept playing.</summary>
        public long PositionNow() => _positionMs + _since.ElapsedMilliseconds;
    }

    private void ReleasePlayer()
    {
        lock (_gate) _app = false;
        MediaPlayer? player;
        HttpRangeStream? stream;
        lock (_gate)
        {
            (player, stream) = (_player, _stream);
            (_player, _stream) = (null, null);
        }
        player?.Pause();
        player?.Dispose();
        stream?.Dispose();
    }

    private void RaiseChanged() => Changed?.Invoke();
}
