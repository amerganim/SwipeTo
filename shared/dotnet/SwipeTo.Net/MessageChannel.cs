using System.Net.WebSockets;
using System.Text;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using SwipeTo.Protocol;

namespace SwipeTo.Net;

/// <summary>Keepalive timings (docs/protocol.md, section 5.7). Tests shorten them.</summary>
public sealed record ChannelTimings(TimeSpan PingInterval, TimeSpan SilenceTimeout, TimeSpan SendTimeout)
{
    public static readonly ChannelTimings Default = new(TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(15), TimeSpan.FromSeconds(5));
}

/// <summary>
/// One SwipeTo session over a WebSocket: text frames of JSON, checked against the
/// <see cref="ProtocolSession"/> state machine in both directions. Answers <c>ping</c> itself, sends
/// <c>ping</c> after 5 s of quiet, and closes after 15 s of silence (docs/protocol.md, section 5.7).
/// A send that does not complete within <see cref="ChannelTimings.SendTimeout"/> drops the session:
/// a stalled connection must never hold up the keepalive watchdog.
/// </summary>
public sealed class MessageChannel : IAsyncDisposable
{
    private const int MaxConsecutiveBadMessages = 3;

    private readonly WebSocket _socket;
    private readonly ILogger _log;
    private readonly ChannelTimings _timings;
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private readonly CancellationTokenSource _closing = new();
    private readonly Task _keepalive;
    private long _lastSentTicks = Environment.TickCount64;
    private long _lastReceivedTicks = Environment.TickCount64;
    private int _badMessages;

    public MessageChannel(WebSocket socket, Role localRole, ILogger? log = null, ChannelTimings? timings = null)
    {
        _socket = socket;
        _log = log ?? NullLogger.Instance;
        _timings = timings ?? ChannelTimings.Default;
        Session = new ProtocolSession(localRole);
        _keepalive = Task.Run(KeepaliveLoop);
    }

    public ProtocolSession Session { get; }

    public bool IsOpen => _socket.State == WebSocketState.Open && !_closing.IsCancellationRequested;

    /// <summary>Checks the message against the state machine, then sends it.</summary>
    public async Task SendAsync(Message message, CancellationToken ct = default)
    {
        await _sendLock.WaitAsync(ct);
        try
        {
            Session.Outgoing(message);
            await SendRawAsync(message, ct);
        }
        finally
        {
            _sendLock.Release();
        }
    }

    /// <summary>
    /// Returns the next message for the application, or <c>null</c> once the connection is closed.
    /// Bad or out-of-state messages are answered with <c>error</c> here and never returned.
    /// </summary>
    public async Task<Message?> ReceiveAsync(CancellationToken ct = default)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, _closing.Token);
        while (true)
        {
            byte[]? frame;
            bool isText;
            try
            {
                (frame, isText) = await ReadFrameAsync(linked.Token);
            }
            catch (Exception e) when (e is WebSocketException or ObjectDisposedException or OperationCanceledException && !ct.IsCancellationRequested)
            {
                if (!_closing.IsCancellationRequested) _log.LogInformation("Connection lost: {Reason}", e.Message);
                return null;
            }
            if (frame is null)
            {
                _log.LogInformation("Connection closed by the other side");
                return null;
            }

            Interlocked.Exchange(ref _lastReceivedTicks, Environment.TickCount64);
            Message message;
            try
            {
                if (!isText) throw new ProtocolException(ErrorCode.BadMessage, "Binary frames are not allowed");
                message = MessageCodec.Parse(frame);
                Session.Incoming(message);
            }
            catch (ProtocolException e)
            {
                _log.LogWarning("Rejected message: {Reason}", e.Message);
                await TrySendUncheckedAsync(e.ToErrorMessage());
                if (e.Code == ErrorCode.BadMessage && ++_badMessages >= MaxConsecutiveBadMessages)
                {
                    await CloseAsync("Too many bad messages");
                    return null;
                }
                continue;
            }

            _badMessages = 0;
            switch (message)
            {
                case Ping ping:
                    await TrySendUncheckedAsync(new Pong { ReplyTo = ping.Id });
                    continue;
                case Pong:
                    continue;
                default:
                    return message;
            }
        }
    }

    public async Task CloseAsync(string reason = "bye")
    {
        if (_closing.IsCancellationRequested) return;
        _closing.Cancel();
        try
        {
            if (_socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
            {
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                await _socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, reason, timeout.Token);
            }
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or ObjectDisposedException)
        {
            // Already gone.
        }
    }

    public async ValueTask DisposeAsync()
    {
        await CloseAsync();
        try { await _keepalive; } catch (OperationCanceledException) { }
        _socket.Dispose();
        _closing.Dispose();
    }

    private async Task<(byte[]? Frame, bool IsText)> ReadFrameAsync(CancellationToken ct)
    {
        var buffer = new byte[8 * 1024];
        using var frame = new MemoryStream();
        while (true)
        {
            var result = await _socket.ReceiveAsync(buffer, ct);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                await CloseAsync();
                return (null, false);
            }
            frame.Write(buffer, 0, result.Count);
            if (frame.Length > MessageCodec.MaxMessageBytes)
            {
                await TrySendUncheckedAsync(new ErrorMessage(ErrorCode.BadMessage, "Message is larger than 64 KiB"));
                await CloseAsync("Message too big");
                return (null, false);
            }
            if (result.EndOfMessage) return (frame.ToArray(), result.MessageType == WebSocketMessageType.Text);
        }
    }

    // Keepalive and error replies bypass Outgoing's state check: they are allowed in every state.
    // With wait: false (pings) it gives up at once if another send holds the lock.
    private async Task TrySendUncheckedAsync(Message message, bool wait = true)
    {
        if (!await _sendLock.WaitAsync(wait ? Timeout.Infinite : 0)) return;
        try
        {
            await SendRawAsync(message, _closing.Token);
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or ObjectDisposedException)
        {
            // The connection is going away; nothing to tell.
        }
        finally
        {
            _sendLock.Release();
        }
    }

    private async Task SendRawAsync(Message message, CancellationToken ct)
    {
        var bytes = Encoding.UTF8.GetBytes(MessageCodec.Serialize(message));
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(_timings.SendTimeout);
        try
        {
            await _socket.SendAsync(bytes, WebSocketMessageType.Text, endOfMessage: true, timeout.Token);
        }
        catch (OperationCanceledException) when (timeout.IsCancellationRequested && !ct.IsCancellationRequested)
        {
            _log.LogWarning("A send stalled for {Seconds} s, dropping the session", _timings.SendTimeout.TotalSeconds);
            Drop();
            throw new WebSocketException(WebSocketError.ConnectionClosedPrematurely, "Send timed out");
        }
        Interlocked.Exchange(ref _lastSentTicks, Environment.TickCount64);
    }

    /// <summary>Ends the session at once, without a closing handshake.</summary>
    private void Drop()
    {
        if (!_closing.IsCancellationRequested) _closing.Cancel();
        _socket.Abort();
    }

    private async Task KeepaliveLoop()
    {
        try
        {
            while (!_closing.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(Math.Min(500, _timings.PingInterval.TotalMilliseconds / 2)), _closing.Token);
                var now = Environment.TickCount64;
                if (now - Interlocked.Read(ref _lastReceivedTicks) >= _timings.SilenceTimeout.TotalMilliseconds)
                {
                    _log.LogInformation("No message for {Seconds} s, dropping the session", _timings.SilenceTimeout.TotalSeconds);
                    Drop();
                    return;
                }
                if (now - Interlocked.Read(ref _lastSentTicks) >= _timings.PingInterval.TotalMilliseconds)
                {
                    // Never wait here: the watchdog above must keep running even if a send is stuck.
                    _ = TrySendUncheckedAsync(new Ping(), wait: false);
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
    }
}
