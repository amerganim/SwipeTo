namespace SwipeTo.Protocol;

/// <summary>Wire names of every v1 message type (docs/protocol.md, section 5).</summary>
public static class MessageTypes
{
    public const string PairRequest = "pair_request";
    public const string PairConfirm = "pair_confirm";
    public const string PairResult = "pair_result";
    public const string Hello = "hello";
    public const string HelloAck = "hello_ack";
    public const string AimStart = "aim_start";
    public const string AimEnd = "aim_end";
    public const string AimAck = "aim_ack";
    public const string Prepare = "prepare";
    public const string Handoff = "handoff";
    public const string HandoffAck = "handoff_ack";
    public const string Control = "control";
    public const string State = "state";
    public const string PullbackRequest = "pullback_request";
    public const string Pullback = "pullback";
    public const string Ping = "ping";
    public const string Pong = "pong";
    public const string Error = "error";
}

public static class ProtocolVersion
{
    public const int Current = 1;

    public static bool IsSupported(int version) => version == Current;
}

/// <summary>Envelope shared by every message. <see cref="Id"/> and <see cref="Ts"/> are filled in on creation.</summary>
public abstract record Message
{
    public string Id { get; init; } = NewId();
    public long Ts { get; init; } = NowMs();
    /// <summary>On a response, the <see cref="Id"/> of the request it answers.</summary>
    public string? ReplyTo { get; init; }

    public abstract string Type { get; }

    public static string NewId() => Guid.NewGuid().ToString("D");
    public static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}

public sealed record PairRequest(string SenderId, string SenderName) : Message
{
    public override string Type => MessageTypes.PairRequest;
}

public sealed record PairConfirm(string Code) : Message
{
    public override string Type => MessageTypes.PairConfirm;
}

/// <summary>Exactly one of <see cref="TrustToken"/> (when <see cref="Ok"/>) or <see cref="Reason"/> is set.</summary>
public sealed record PairResult(bool Ok, string? TrustToken, PairFailure? Reason) : Message
{
    public override string Type => MessageTypes.PairResult;

    public static PairResult Success(string trustToken) => new(true, trustToken, null);
    public static PairResult Failure(PairFailure reason) => new(false, null, reason);
}

public sealed record Hello(string SenderId, string SenderName, string TrustToken, int ProtocolVersion) : Message
{
    public override string Type => MessageTypes.Hello;
}

public sealed record HelloAck(string ReceiverId, bool Accepted, ErrorCode? Reason = null) : Message
{
    public override string Type => MessageTypes.HelloAck;
}

/// <summary>
/// With <see cref="Nonce"/>: the marker nonce the phone's camera read, asking the receiver to confirm the lock.
/// With <see cref="Prefetch"/>: what is about to be handed off, so the receiver can start loading it.
/// </summary>
public sealed record AimStart(string? Nonce = null, Prefetch? Prefetch = null) : Message
{
    public override string Type => MessageTypes.AimStart;
}

/// <summary>A hint to open <see cref="Media"/> at <see cref="PositionMs"/> before the <c>handoff</c> arrives.</summary>
public sealed record Prefetch(MediaInfo Media, long PositionMs);

/// <summary>Answer to an <c>aim_start</c> with a nonce: locked if it is the receiver's current or previous marker nonce.</summary>
public sealed record AimAck(bool Locked) : Message
{
    public override string Type => MessageTypes.AimAck;
}

public sealed record AimEnd : Message
{
    public override string Type => MessageTypes.AimEnd;
}

/// <summary>While the user watches: open <see cref="Media"/> hidden and muted, in step with <see cref="PositionMs"/>.</summary>
public sealed record Prepare(MediaInfo Media, long PositionMs) : Message
{
    public override string Type => MessageTypes.Prepare;
}

public sealed record MediaInfo(MediaKind Kind, string Url, string Mime, string Title, long? DurationMs = null);

/// <summary><see cref="Transition"/>: the media follows on from what is showing (album navigation).</summary>
public sealed record Handoff(MediaInfo Media, long PositionMs, bool Playing, Transition? Transition = null) : Message
{
    public override string Type => MessageTypes.Handoff;
}

public sealed record HandoffAck(bool Accepted, ErrorCode? Reason = null) : Message
{
    public override string Type => MessageTypes.HandoffAck;
}

/// <summary><see cref="PositionMs"/> is required for <see cref="ControlAction.Seek"/> and ignored otherwise.</summary>
public sealed record Control(ControlAction Action, long? PositionMs = null) : Message
{
    public override string Type => MessageTypes.Control;
}

/// <summary>
/// The <c>state</c> message: receiver playback state. <see cref="Playing"/> is the intent;
/// <see cref="Buffering"/> says no frames are showing yet.
/// </summary>
public sealed record PlaybackState(long PositionMs, bool Playing, bool Ended, bool Buffering = false) : Message
{
    public override string Type => MessageTypes.State;
}

public sealed record PullbackRequest : Message
{
    public override string Type => MessageTypes.PullbackRequest;
}

public sealed record Pullback(long PositionMs, bool Playing) : Message
{
    public override string Type => MessageTypes.Pullback;
}

public sealed record Ping : Message
{
    public override string Type => MessageTypes.Ping;
}

public sealed record Pong : Message
{
    public override string Type => MessageTypes.Pong;
}

/// <summary>The <c>error</c> message. <see cref="Description"/> is the wire field <c>message</c>.</summary>
public sealed record ErrorMessage(ErrorCode Code, string Description) : Message
{
    public override string Type => MessageTypes.Error;
}
