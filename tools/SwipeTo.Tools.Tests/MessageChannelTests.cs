using System.Diagnostics;
using System.Net.WebSockets;
using SwipeTo.Net;
using SwipeTo.Protocol;

namespace SwipeTo.Tools.Tests;

/// <summary>Keepalive behaviour when the other side stops cooperating.</summary>
public sealed class MessageChannelTests
{
    private static readonly ChannelTimings Fast = new(
        PingInterval: TimeSpan.FromMilliseconds(100),
        SilenceTimeout: TimeSpan.FromMilliseconds(600),
        SendTimeout: TimeSpan.FromMilliseconds(300));

    [Fact]
    public async Task A_send_that_never_completes_drops_the_session()
    {
        var socket = new StalledWebSocket();
        await using var channel = new MessageChannel(socket, Role.Receiver, timings: Fast with { PingInterval = TimeSpan.FromHours(1) });
        var clock = Stopwatch.StartNew();

        await Assert.ThrowsAsync<WebSocketException>(() => channel.SendAsync(new Ping()));

        Assert.InRange(clock.ElapsedMilliseconds, 200, 3000);
        Assert.True(socket.Aborted);
        Assert.False(channel.IsOpen);
        Assert.Null(await channel.ReceiveAsync().WaitAsync(TimeSpan.FromSeconds(2)));
    }

    [Fact]
    public async Task Silence_watchdog_fires_even_while_a_send_is_stuck()
    {
        // The keepalive ping gets stuck in SendAsync; the silence timeout must still end the session.
        var socket = new StalledWebSocket();
        await using var channel = new MessageChannel(socket, Role.Receiver, timings: Fast with { SendTimeout = TimeSpan.FromHours(1) });

        var received = await channel.ReceiveAsync().WaitAsync(TimeSpan.FromSeconds(3));

        Assert.Null(received);
        Assert.True(socket.Aborted);
        Assert.True(socket.SendsStarted >= 1, "a ping should have been attempted");
    }

    /// <summary>A peer that never reads (sends hang) and never writes (receives hang) until aborted.</summary>
    private sealed class StalledWebSocket : WebSocket
    {
        private readonly CancellationTokenSource _aborted = new();
        private int _sendsStarted;

        public bool Aborted => _aborted.IsCancellationRequested;
        public int SendsStarted => _sendsStarted;

        public override WebSocketCloseStatus? CloseStatus => null;
        public override string? CloseStatusDescription => null;
        public override WebSocketState State => Aborted ? WebSocketState.Aborted : WebSocketState.Open;
        public override string? SubProtocol => null;

        public override void Abort() => _aborted.Cancel();

        public override Task CloseAsync(WebSocketCloseStatus closeStatus, string? statusDescription, CancellationToken cancellationToken) => Task.CompletedTask;

        public override Task CloseOutputAsync(WebSocketCloseStatus closeStatus, string? statusDescription, CancellationToken cancellationToken) =>
            Hang(cancellationToken);

        public override void Dispose() => _aborted.Dispose();

        public override async Task<WebSocketReceiveResult> ReceiveAsync(ArraySegment<byte> buffer, CancellationToken cancellationToken)
        {
            await Hang(cancellationToken);
            throw new WebSocketException("unreachable");
        }

        public override Task SendAsync(ArraySegment<byte> buffer, WebSocketMessageType messageType, bool endOfMessage, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref _sendsStarted);
            return Hang(cancellationToken);
        }

        private async Task Hang(CancellationToken cancellationToken)
        {
            using var either = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, _aborted.Token);
            await Task.Delay(Timeout.Infinite, either.Token);
        }
    }
}
