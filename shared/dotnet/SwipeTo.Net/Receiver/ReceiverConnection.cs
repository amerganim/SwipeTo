using System.Net.WebSockets;
using Microsoft.Extensions.Logging;
using SwipeTo.Protocol;

namespace SwipeTo.Net.Receiver;

/// <summary>One sender's WebSocket session on a receiver.</summary>
internal sealed class ReceiverConnection
{
    private readonly ReceiverHost _host;
    private readonly MessageChannel _channel;
    private readonly ILogger _log;
    private readonly ReceiverOptions _options;

    private string? _pairingCode;
    private DateTimeOffset _pairingIssuedAt;
    private int _pairingAttempts;
    private string? _pairingSenderId;
    private string? _pairingSenderName;
    private string? _senderName;
    private CancellationTokenSource? _aimTimeout;
    private CancellationTokenSource? _prepared;
    private CancellationTokenSource? _markerRotation;
    private bool _aimLocked;
    private CancellationTokenSource? _stateTicker;

    public ReceiverConnection(ReceiverHost host, WebSocket socket, ILogger log)
    {
        _host = host;
        _options = host.Options;
        _log = log;
        _channel = new MessageChannel(socket, Role.Receiver, log);
    }

    public string? SenderId { get; private set; }

    public bool IsOpen => _channel.IsOpen;

    public async Task RunAsync(CancellationToken ct)
    {
        try
        {
            while (await _channel.ReceiveAsync(ct) is { } message)
            {
                await HandleAsync(message);
            }
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            _log.LogInformation("Session with {Sender} ended", _senderName ?? _pairingSenderName ?? "an unpaired sender");
            Cleanup();
            await _channel.DisposeAsync();
        }
    }

    private Task HandleAsync(Message message) => message switch
    {
        PairRequest m => OnPairRequestAsync(m),
        PairConfirm m => OnPairConfirmAsync(m),
        Hello m => OnHelloAsync(m),
        AimStart m => OnAimStartAsync(m),
        AimEnd => OnAimEndAsync(),
        Prepare m => OnPrepareAsync(m),
        Handoff m => OnHandoffAsync(m),
        Control m => OnControlAsync(m),
        PullbackRequest m => OnPullbackAsync(m),
        ErrorMessage m => LogPeerError(m),
        _ => Task.CompletedTask,
    };

    private Task OnPairRequestAsync(PairRequest request)
    {
        // At most one fresh code per interval; a repeat inside it keeps the code on screen.
        var now = DateTimeOffset.UtcNow;
        if (_pairingCode is null || now - _pairingIssuedAt >= _options.PairRequestInterval)
        {
            _pairingCode = _options.FixedPairingCode ?? Secrets.PairingCode();
            _pairingIssuedAt = now;
            _pairingAttempts = 0;
        }
        _pairingSenderId = request.SenderId;
        _pairingSenderName = request.SenderName;
        _host.Ui.ShowPairingCode(_pairingCode, request.SenderName);
        _log.LogInformation("Pairing requested by {Sender}", request.SenderName);
        return Task.CompletedTask;
    }

    private async Task OnPairConfirmAsync(PairConfirm confirm)
    {
        PairResult result;
        if (_pairingCode is null || DateTimeOffset.UtcNow - _pairingIssuedAt > _options.PairingCodeLifetime)
        {
            result = PairResult.Failure(PairFailure.CodeExpired);
        }
        else if (Secrets.FixedTimeEquals(confirm.Code, _pairingCode))
        {
            var token = Secrets.Token();
            _host.Trust.Add(_pairingSenderId!, _pairingSenderName!, token);
            result = PairResult.Success(token);
            _log.LogInformation("Paired with {Sender}", _pairingSenderName);
        }
        else
        {
            result = PairResult.Failure(++_pairingAttempts >= _options.PairingAttempts ? PairFailure.TooManyAttempts : PairFailure.WrongCode);
        }

        if (result.Reason != PairFailure.WrongCode) EndPairing();
        await _channel.SendAsync(result with { ReplyTo = confirm.Id });
    }

    private async Task OnHelloAsync(Hello hello)
    {
        ErrorCode? refusal =
            !ProtocolVersion.IsSupported(hello.ProtocolVersion) ? ErrorCode.VersionMismatch
            : !_host.Trust.IsTrusted(hello.SenderId, hello.TrustToken) ? ErrorCode.NotPaired
            : _host.IsBusyFor(hello.SenderId) ? ErrorCode.Busy
            : null;

        if (refusal is null)
        {
            SenderId = hello.SenderId;
            _senderName = hello.SenderName;
            _host.OnSenderConnected(hello.SenderName);
        }
        _log.LogInformation("hello from {Sender}: {Outcome}", hello.SenderName, refusal?.ToString() ?? "accepted");
        await _channel.SendAsync(new HelloAck(_options.ReceiverId, refusal is null, refusal) { ReplyTo = hello.Id });
    }

    private async Task OnAimStartAsync(AimStart aim)
    {
        if (aim.Nonce is { } nonce)
        {
            // The phone read our marker: confirm (or refuse) the lock.
            var locked = _host.Markers.IsValid(nonce);
            if (locked) _aimLocked = true;
            _log.LogInformation("Aim lock {Outcome}", locked ? "confirmed" : "refused (stale or unknown nonce)");
            await _channel.SendAsync(new AimAck(locked) { ReplyTo = aim.Id });
        }

        if (aim.Prefetch is { } prefetch && _host.Playback.CanPlay(prefetch.Media))
        {
            // Start loading what is about to be handed off (no-op if this URL is already prefetched).
            _host.Playback.Prefetch(prefetch.Media, prefetch.PositionMs);
        }

        _host.Ui.ShowAim(_host.Markers.Payload(_options.ReceiverId), _aimLocked);
        StartMarkerRotation();
        _aimTimeout?.Cancel();
        var timeout = _aimTimeout = new CancellationTokenSource();
        _ = Task.Delay(_options.AimTimeout, timeout.Token).ContinueWith(t =>
        {
            if (t.IsCanceled) return;
            HideAim();
            DiscardPrefetchUnlessPrepared();
        }, TaskScheduler.Default);
    }

    /// <summary>
    /// The phone is watching this and may hand it to us: load it hidden and muted, in step with it, so a
    /// later handoff starts at once. Nothing shows. Dropped if the repeats stop.
    /// </summary>
    private Task OnPrepareAsync(Prepare prepare)
    {
        if (prepare.Media.Kind != MediaKind.Video || !_host.Playback.CanPlay(prepare.Media)) return Task.CompletedTask;
        if (_prepared is null) _log.LogInformation("Preparing {Title} at {Position} ms", prepare.Media.Title, prepare.PositionMs);
        _host.Playback.Prefetch(prepare.Media, prepare.PositionMs);

        _prepared?.Cancel();
        var expiry = _prepared = new CancellationTokenSource();
        _ = Task.Delay(_options.PrepareTimeout, expiry.Token).ContinueWith(t =>
        {
            if (t.IsCanceled) return;
            _prepared = null;
            _host.Playback.DiscardPrefetch();
            _log.LogInformation("Prepared media dropped: no prepare for {Seconds} s", _options.PrepareTimeout.TotalSeconds);
        }, TaskScheduler.Default);
        return Task.CompletedTask;
    }

    /// <summary>Finds app content with its service; any failure is a refusal, never a guess.</summary>
    private async Task<bool> ResolveAppAsync(MediaInfo media)
    {
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
            var found = await _host.Playback.ResolveAppAsync(media, timeout.Token);
            _log.LogInformation("{Service} content \"{Title}\" {Outcome}", media.Service, media.Title, found ? "found" : "not found");
            return found;
        }
        catch (Exception e)
        {
            _log.LogWarning("{Service} lookup failed: {Error}", media.Service, e.Message);
            return false;
        }
    }

    private void StopPreparing()
    {
        _prepared?.Cancel();
        _prepared = null;
    }

    /// <summary>The aim is over; what the phone is still preparing stays loaded.</summary>
    private void DiscardPrefetchUnlessPrepared()
    {
        if (_prepared is null) _host.Playback.DiscardPrefetch();
    }

    /// <summary>A new marker nonce every rotation while the marker is up, so a photo of it goes stale.</summary>
    private void StartMarkerRotation()
    {
        if (_markerRotation is not null) return;
        var rotation = _markerRotation = new CancellationTokenSource();
        _ = Task.Run(async () =>
        {
            using var timer = new PeriodicTimer(_options.MarkerRotation);
            while (await timer.WaitForNextTickAsync(rotation.Token).ConfigureAwait(false))
            {
                _host.Markers.Rotate();
                if (!rotation.IsCancellationRequested) _host.Ui.ShowAim(_host.Markers.Payload(_options.ReceiverId), _aimLocked);
            }
        }).ContinueWith(_ => { }, TaskScheduler.Default);
    }

    private Task OnAimEndAsync()
    {
        HideAim();
        DiscardPrefetchUnlessPrepared();
        return Task.CompletedTask;
    }

    private async Task OnHandoffAsync(Handoff handoff)
    {
        StopPreparing(); // a handoff takes what was prepared (or replaces it)
        HideAim();
        if (!_host.Playback.CanPlay(handoff.Media))
        {
            var reason = handoff.Media.IsApp ? ErrorCode.ServiceUnavailable : ErrorCode.UnsupportedMedia;
            await _channel.SendAsync(new HandoffAck(false, reason) { ReplyTo = handoff.Id });
            return;
        }
        if (handoff.Media.IsApp && _host.Playback.CurrentUrl != handoff.Media.Key && !await ResolveAppAsync(handoff.Media))
        {
            await _channel.SendAsync(new HandoffAck(false, ErrorCode.ServiceUnavailable) { ReplyTo = handoff.Id });
            return;
        }
        if (!_host.TryClaim(this))
        {
            await _channel.SendAsync(new HandoffAck(false, ErrorCode.Busy) { ReplyTo = handoff.Id });
            return;
        }

        StopStateTicker();
        _host.Playback.Changed -= OnPlaybackChanged;
        await _channel.SendAsync(new HandoffAck(true) { ReplyTo = handoff.Id });

        var playback = _host.Playback;
        var now = playback.Snapshot;
        if (playback.CurrentUrl == handoff.Media.Key && !now.Ended)
        {
            // Same media again (a phone that reconnected): carry on from where the viewer is, no reload,
            // no seek; the phone's position is only its estimate (docs/protocol.md, 5.4).
            if (handoff.Playing) playback.Play(); else playback.Pause();
            _log.LogInformation("Re-attached to {Kind} already playing at {Position} ms", handoff.Media.Kind, now.PositionMs);
            playback.Changed += OnPlaybackChanged;
            await SendStateAsync();
            StartStateTicker();
            return;
        }
        _log.LogInformation("Handoff of {Kind} at {Position} ms{Transition}", handoff.Media.Kind, handoff.PositionMs,
            handoff.Transition is { } t ? $" ({t.ToString().ToLowerInvariant()})" : "");

        try
        {
            await _host.Playback.LoadAsync(handoff.Media, handoff.PositionMs, handoff.Playing, handoff.Transition, CancellationToken.None);
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            _log.LogWarning("Media failed to load: {Error}", e.Message);
            _host.Release(this);
            await TrySendAsync(new ErrorMessage(ErrorCode.UnsupportedMedia, "The media could not be loaded"));
            await TrySendAsync(new PlaybackState(handoff.PositionMs, false, true));
            return;
        }

        _host.Playback.Changed += OnPlaybackChanged;
        await SendStateAsync();
        StartStateTicker();
    }

    private Task OnControlAsync(Control control)
    {
        var playback = _host.Playback;
        switch (control.Action)
        {
            case ControlAction.Play: playback.Play(); break;
            case ControlAction.Pause: playback.Pause(); break;
            case ControlAction.Seek: playback.Seek(control.PositionMs!.Value); break;
            case ControlAction.Stop: playback.Stop(); break;
        }
        return Task.CompletedTask;
    }

    private async Task OnPullbackAsync(PullbackRequest request)
    {
        var snapshot = _host.Playback.Snapshot;
        DetachPlayback();
        _host.Playback.Stop();
        await _channel.SendAsync(new Pullback(snapshot.PositionMs, snapshot.Playing) { ReplyTo = request.Id });
        _log.LogInformation("Pulled back at {Position} ms", snapshot.PositionMs);
    }

    private Task LogPeerError(ErrorMessage error)
    {
        _log.LogWarning("Sender reported {Code}: {Description}", error.Code, error.Description);
        return Task.CompletedTask;
    }

    private void OnPlaybackChanged() => _ = SendStateAsync();

    private async Task SendStateAsync()
    {
        if (_channel.Session.State != SessionState.HandoffActive || !_host.IsActive(this)) return;
        var snapshot = _host.Playback.Snapshot;
        if (snapshot.Ended) DetachPlayback();
        await TrySendAsync(new PlaybackState(snapshot.PositionMs, snapshot.Playing, snapshot.Ended, snapshot.Buffering));
    }

    private void StartStateTicker()
    {
        var ticker = _stateTicker = new CancellationTokenSource();
        _ = Task.Run(async () =>
        {
            using var timer = new PeriodicTimer(_options.StateInterval);
            while (await timer.WaitForNextTickAsync(ticker.Token).ConfigureAwait(false))
            {
                await SendStateAsync();
            }
        }).ContinueWith(_ => { }, TaskScheduler.Default);
    }

    private void StopStateTicker()
    {
        _stateTicker?.Cancel();
        _stateTicker = null;
    }

    /// <summary>Stops reporting state for this connection; the media itself is left alone.</summary>
    private void DetachPlayback()
    {
        StopStateTicker();
        _host.Playback.Changed -= OnPlaybackChanged;
        _host.Release(this);
    }

    private async Task TrySendAsync(Message message)
    {
        try
        {
            await _channel.SendAsync(message);
        }
        catch (Exception e) when (e is InvalidOperationException or WebSocketException or ObjectDisposedException or OperationCanceledException)
        {
            // State moved on (e.g. pull-back crossed a state update) or the sender left.
        }
    }

    private void EndPairing()
    {
        _pairingCode = null;
        _host.Ui.HidePairingCode();
    }

    private void HideAim()
    {
        _aimTimeout?.Cancel();
        _markerRotation?.Cancel();
        _markerRotation = null;
        _aimLocked = false;
        _host.Ui.HideAim();
    }

    private void Cleanup()
    {
        // The viewer keeps watching if the phone drops; we just stop reporting to it.
        DetachPlayback();
        if (_pairingCode is not null) EndPairing();
        if (_aimTimeout is not null) HideAim();
        if (_aimTimeout is not null || _prepared is not null)
        {
            StopPreparing();
            _host.Playback.DiscardPrefetch();
        }
        if (_senderName is not null) _host.OnSenderDisconnected(_senderName);
    }
}
