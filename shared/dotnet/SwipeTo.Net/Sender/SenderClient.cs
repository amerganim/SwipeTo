using System.Collections.Concurrent;
using System.Net.WebSockets;
using Microsoft.Extensions.Logging;
using SwipeTo.Protocol;

namespace SwipeTo.Net.Sender;

public sealed record SenderIdentity(string SenderId, string SenderName);

/// <summary>
/// The phone side of one session, for .NET senders (the mock sender and tests). Requests are matched
/// to responses by <c>replyTo</c>; <c>state</c> and stray <c>error</c> messages surface as events.
/// </summary>
public sealed class SenderClient : IAsyncDisposable
{
    private static readonly TimeSpan ResponseTimeout = TimeSpan.FromSeconds(10);

    private readonly MessageChannel _channel;
    private readonly SenderIdentity _identity;
    private readonly ConcurrentDictionary<string, TaskCompletionSource<Message>> _pending = new();
    private readonly Task _receiveLoop;

    private SenderClient(ClientWebSocket socket, SenderIdentity identity, ILogger? log)
    {
        _identity = identity;
        _channel = new MessageChannel(socket, Role.Sender, log);
        _receiveLoop = Task.Run(ReceiveLoopAsync);
    }

    public SessionState State => _channel.Session.State;

    public PlaybackState? LastState { get; private set; }

    public event Action<PlaybackState>? StateReceived;
    public event Action<ErrorMessage>? ErrorReceived;
    public event Action? Disconnected;

    public static async Task<SenderClient> ConnectAsync(Uri uri, SenderIdentity identity, ILogger? log = null, CancellationToken ct = default)
    {
        var socket = new ClientWebSocket();
        socket.Options.KeepAliveInterval = TimeSpan.Zero; // the protocol has its own ping
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(5));
        try
        {
            await socket.ConnectAsync(uri, timeout.Token);
        }
        catch
        {
            socket.Dispose();
            throw;
        }
        return new SenderClient(socket, identity, log);
    }

    /// <summary>
    /// Pairs and returns the trust token. <paramref name="askForCode"/> is called for each attempt
    /// (the user reads the code off the screen); returns null when pairing fails for good.
    /// </summary>
    public async Task<string?> PairAsync(Func<int, Task<string>> askForCode, int maxAttempts = 3)
    {
        await _channel.SendAsync(new PairRequest(_identity.SenderId, _identity.SenderName));
        for (var attempt = 1; attempt <= maxAttempts; attempt++)
        {
            var code = await askForCode(attempt);
            var result = await RequestAsync<PairResult>(new PairConfirm(code));
            if (result.Ok) return result.TrustToken;
            if (result.Reason != PairFailure.WrongCode) return null;
        }
        return null;
    }

    public Task<HelloAck> HelloAsync(string trustToken) =>
        RequestAsync<HelloAck>(new Hello(_identity.SenderId, _identity.SenderName, trustToken, ProtocolVersion.Current));

    public Task AimStartAsync(Prefetch? prefetch = null) => _channel.SendAsync(new AimStart(Prefetch: prefetch));

    public Task PrepareAsync(MediaInfo media, long positionMs) => _channel.SendAsync(new Prepare(media, positionMs));

    /// <summary>Reports the marker nonce the camera read; the receiver says whether it locks.</summary>
    public Task<AimAck> LockAsync(string nonce) => RequestAsync<AimAck>(new AimStart(nonce));

    public Task AimEndAsync() => _channel.SendAsync(new AimEnd());

    public Task<HandoffAck> HandoffAsync(MediaInfo media, long positionMs, bool playing, Transition? transition = null) =>
        RequestAsync<HandoffAck>(new Handoff(media, positionMs, playing, transition));

    public Task ControlAsync(ControlAction action, long? positionMs = null) => _channel.SendAsync(new Control(action, positionMs));

    public Task<Pullback> PullbackAsync() => RequestAsync<Pullback>(new PullbackRequest());

    /// <summary>Waits for the next <c>state</c> matching <paramref name="predicate"/>.</summary>
    public async Task<PlaybackState> WaitForStateAsync(Func<PlaybackState, bool> predicate, TimeSpan? timeout = null)
    {
        var tcs = new TaskCompletionSource<PlaybackState>(TaskCreationOptions.RunContinuationsAsynchronously);
        void Handler(PlaybackState s)
        {
            if (predicate(s)) tcs.TrySetResult(s);
        }
        StateReceived += Handler;
        try
        {
            return await tcs.Task.WaitAsync(timeout ?? ResponseTimeout);
        }
        finally
        {
            StateReceived -= Handler;
        }
    }

    public async ValueTask DisposeAsync()
    {
        await _channel.DisposeAsync();
        try { await _receiveLoop; } catch (OperationCanceledException) { }
    }

    private async Task<T> RequestAsync<T>(Message request) where T : Message
    {
        var tcs = new TaskCompletionSource<Message>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pending[request.Id] = tcs;
        try
        {
            await _channel.SendAsync(request);
            var response = await tcs.Task.WaitAsync(ResponseTimeout);
            return response as T ?? throw new ProtocolException(ErrorCode.BadMessage, $"Expected {typeof(T).Name}, got {response.Type}");
        }
        finally
        {
            _pending.TryRemove(request.Id, out _);
        }
    }

    private async Task ReceiveLoopAsync()
    {
        try
        {
            while (await _channel.ReceiveAsync() is { } message)
            {
                if (message.ReplyTo is { } replyTo && _pending.TryGetValue(replyTo, out var waiter))
                {
                    if (message is ErrorMessage error) waiter.TrySetException(new ProtocolException(error.Code, error.Description, replyTo));
                    else waiter.TrySetResult(message);
                    continue;
                }
                switch (message)
                {
                    case PlaybackState state:
                        LastState = state;
                        StateReceived?.Invoke(state);
                        break;
                    case ErrorMessage error:
                        ErrorReceived?.Invoke(error);
                        break;
                }
            }
        }
        catch (WebSocketException)
        {
        }
        finally
        {
            foreach (var waiter in _pending.Values) waiter.TrySetException(new WebSocketException("Connection closed"));
            Disconnected?.Invoke();
        }
    }
}
