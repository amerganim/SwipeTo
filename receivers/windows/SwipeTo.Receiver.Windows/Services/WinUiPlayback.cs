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
internal sealed class WinUiPlayback(DispatcherQueue ui, string dataDir, ILogger log) : IPlaybackTarget
{
    private static readonly TimeSpan OpenTimeout = TimeSpan.FromSeconds(15);
    private static readonly HashSet<string> ImageTypes = ["image/jpeg", "image/png", "image/gif", "image/bmp", "image/webp", "image/tiff"];
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(15) };

    private readonly object _gate = new();

    private PlayerWindow? _window;
    private MediaPlayer? _player;
    private HttpRangeStream? _stream;
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
        _ => false,
    };

    public async Task LoadAsync(MediaInfo media, long positionMs, bool playing, CancellationToken ct)
    {
        var clock = Stopwatch.StartNew();

        if (media.Kind == MediaKind.Image)
        {
            await ui.RunAsync(() =>
            {
                ReleasePlayer();
                EnsureWindow().ShowPhoto(new Uri(media.Url), media.Title);
                lock (_gate)
                {
                    _ended = false;
                    _wantPlaying = false;
                    _lastPositionMs = positionMs;
                }
            });
            log.LogInformation("Photo shown after {Ms} ms", clock.ElapsedMilliseconds);
            return;
        }

        // Feed the player a range-reading stream rather than the URL: the built-in HTTP source buffers
        // about two seconds before it starts, this starts as soon as the first frames have arrived.
        var stream = await HttpRangeStream.OpenAsync(Http, new Uri(media.Url), ct: ct);
        var player = new MediaPlayer { AutoPlay = false, AudioCategory = MediaPlayerAudioCategory.Movie };
        var opened = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        player.MediaOpened += (_, _) => opened.TrySetResult();
        player.MediaFailed += (_, e) => opened.TrySetException(new InvalidOperationException($"{e.Error}: {e.ErrorMessage}"));
        player.Source = MediaSource.CreateFromStream(stream.AsRandomAccessStream(), media.Mime);

        // Go full screen (black) while the media opens, rather than one after the other.
        await ui.RunAsync(() =>
        {
            ReleasePlayer();
            EnsureWindow().ShowVideo(player, media.Title);
        });
        var shownMs = clock.ElapsedMilliseconds;

        try
        {
            await opened.Task.WaitAsync(OpenTimeout, ct);
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
        session.Position = TimeSpan.FromMilliseconds(positionMs);
        lock (_gate)
        {
            _player = player;
            _stream = stream;
            _ended = false;
            _wantPlaying = playing;
            _lastPositionMs = positionMs;
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
                log.LogInformation("First frame playing {Ms} ms after handoff at {Position} ms", clock.ElapsedMilliseconds, (long)s.Position.TotalMilliseconds);
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

        if (playing) player.Play();
        log.LogInformation("Video window up after {Shown} ms, media opened after {Opened} ms, at {Position} ms", shownMs, openedMs, positionMs);
    }

    public void Play()
    {
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
        lock (_gate)
        {
            if (_player is null) return;
            _player.PlaybackSession.Position = TimeSpan.FromMilliseconds(positionMs);
        }
        // SeekCompleted raises Changed once the new position is real.
    }

    public void Stop() => ui.TryEnqueue(() => EndPlayback("by the phone"));

    public PlaybackSnapshot Snapshot
    {
        get
        {
            lock (_gate)
            {
                if (_player is not null)
                {
                    try
                    {
                        _lastPositionMs = (long)_player.PlaybackSession.Position.TotalMilliseconds;
                    }
                    catch (ObjectDisposedException)
                    {
                    }
                }
                // Report intent while buffering so the phone's play button does not flicker.
                return new PlaybackSnapshot(_lastPositionMs, _wantPlaying && !_ended, _ended);
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
        _window = new PlayerWindow();
        _window.ViewerClosed += () => EndPlayback("by the viewer");
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

    private void ReleasePlayer()
    {
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
