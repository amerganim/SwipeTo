namespace SwipeTo.Protocol;

public enum Role
{
    Sender,
    Receiver,
}

public enum SessionState
{
    Connected,
    Pairing,
    Paired,
    Session,
    HandoffActive,
}

/// <summary>
/// The v1 session state machine (docs/protocol.md, section 4) for one WebSocket connection, seen from
/// one side. Thread-safe. Call <see cref="Outgoing"/> before sending and <see cref="Incoming"/> after parsing; both
/// check the message is allowed in the current state and then apply its transition.
/// </summary>
public sealed class ProtocolSession(Role localRole)
{
    private readonly object _gate = new();

    public Role LocalRole { get; } = localRole;
    public SessionState State { get; private set; } = SessionState.Connected;

    public bool HasSession => State is SessionState.Session or SessionState.HandoffActive;

    /// <summary>Throws <see cref="InvalidOperationException"/> if this side may not send <paramref name="message"/> now.</summary>
    public void Outgoing(Message message)
    {
        lock (_gate)
        {
            if (!IsAllowed(message, LocalRole, State))
            {
                throw new InvalidOperationException($"{LocalRole} may not send {message.Type} in state {State}");
            }
            Apply(message);
        }
    }

    /// <summary>Throws <see cref="ProtocolException"/> (the code to reply with) if the peer may not send <paramref name="message"/> now.</summary>
    public void Incoming(Message message)
    {
        var author = LocalRole == Role.Sender ? Role.Receiver : Role.Sender;
        lock (_gate)
        {
            if (!IsAllowed(message, author, State))
            {
                var code = author == Role.Sender && !HasSession && NeedsSession(message) ? ErrorCode.NotPaired : ErrorCode.BadMessage;
                throw new ProtocolException(code, $"{message.Type} is not allowed in state {State}", message.Id);
            }
            Apply(message);
        }
    }

    public static bool IsAllowed(Message message, Role author, SessionState state)
    {
        if (message is Ping or Pong or ErrorMessage) return true;

        return (author, state) switch
        {
            (Role.Sender, SessionState.Connected or SessionState.Paired) => message is PairRequest or Hello,
            (Role.Sender, SessionState.Pairing) => message is PairRequest or PairConfirm,
            (Role.Sender, SessionState.Session) => message is AimStart or AimEnd or Handoff,
            (Role.Sender, SessionState.HandoffActive) => message is AimStart or AimEnd or Handoff or Control or PullbackRequest,
            (Role.Receiver, SessionState.Connected or SessionState.Paired) => message is HelloAck,
            (Role.Receiver, SessionState.Pairing) => message is PairResult,
            (Role.Receiver, SessionState.Session) => message is HandoffAck or PlaybackState,
            (Role.Receiver, SessionState.HandoffActive) => message is HandoffAck or PlaybackState or Pullback,
            _ => false,
        };
    }

    private static bool NeedsSession(Message message) =>
        message is AimStart or AimEnd or Handoff or Control or PullbackRequest;

    private void Apply(Message message)
    {
        State = (message, State) switch
        {
            (PairRequest, _) => SessionState.Pairing,
            (PairResult { Ok: true }, _) => SessionState.Paired,
            (PairResult { Reason: PairFailure.CodeExpired or PairFailure.TooManyAttempts }, _) => SessionState.Connected,
            (HelloAck { Accepted: true }, _) => SessionState.Session,
            (HandoffAck { Accepted: true }, _) => SessionState.HandoffActive,
            (Pullback, _) => SessionState.Session,
            (PlaybackState { Ended: true }, SessionState.HandoffActive) => SessionState.Session,
            _ => State,
        };
    }
}
