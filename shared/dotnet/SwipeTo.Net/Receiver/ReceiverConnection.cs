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

        _host.Ui.ShowAim(_host.Markers.Payload(_options.ReceiverId), _aimLocked);
        StartMarkerRotation();
        _aimTimeout?.Cancel();
        var timeout = _aimTimeout = new CancellationTokenSource();
        _ = Task.Delay(_options.AimTimeout, timeout.Token).ContinueWith(t =>
        {
            if (!t.IsCanceled) HideAim();
        }, TaskScheduler.Default);
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
        return Task.CompletedTask;
    }

    private async Task OnHandoffAsync(Handoff handoff)
    {
        HideAim();
        if (!_host.Playback.CanPlay(handoff.Media))
        {
            await _channel.SendAsync(new HandoffAck(false, ErrorCode.UnsupportedMedia) { ReplyTo = handoff.Id });
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
        _log.LogInformation("Handoff of {Kind} at {Position} ms", handoff.Media.Kind, handoff.PositionMs);

        try
        {
            await _host.Playback.LoadAsync(handoff.Media, handoff.PositionMs, handoff.Playing, CancellationToken.None);
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
        await TrySendAsync(new PlaybackState(snapshot.PositionMs, snapshot.Playing, snapshot.Ended));
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
        if (_senderName is not null) _host.OnSenderDisconnected(_senderName);
    }
}
