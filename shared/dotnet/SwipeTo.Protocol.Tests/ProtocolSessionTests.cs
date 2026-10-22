namespace SwipeTo.Protocol.Tests;

public class ProtocolSessionTests
{
    private const string SenderId = "a1b2c3d4-e5f6-4789-8abc-def012345678";
    private const string ReceiverId = "0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21";
    private const string Token = "q8Zr3kP0vX2mN7bT5wY1cA9dE4fG6hJ";
    private static readonly MediaInfo Video = new(MediaKind.Video, "http://10.0.0.2:8765/m/tok/f1", "video/mp4", "clip");

    /// <summary>Drives both ends with the same message so their states must stay in step.</summary>
    private static void Exchange(ProtocolSession phone, ProtocolSession screen, Message message, Role author)
    {
        var (from, to) = author == Role.Sender ? (phone, screen) : (screen, phone);
        from.Outgoing(message);
        to.Incoming(message);
        Assert.Equal(phone.State, screen.State);
    }

    [Fact]
    public void Full_flow_walks_every_state()
    {
        var phone = new ProtocolSession(Role.Sender);
        var screen = new ProtocolSession(Role.Receiver);

        Exchange(phone, screen, new PairRequest(SenderId, "Pixel"), Role.Sender);
        Assert.Equal(SessionState.Pairing, phone.State);
        Exchange(phone, screen, new PairConfirm("111111"), Role.Sender);
        Exchange(phone, screen, PairResult.Failure(PairFailure.WrongCode), Role.Receiver);
        Assert.Equal(SessionState.Pairing, phone.State);
        Exchange(phone, screen, new PairConfirm("048213"), Role.Sender);
        Exchange(phone, screen, PairResult.Success(Token), Role.Receiver);
        Assert.Equal(SessionState.Paired, phone.State);
        Exchange(phone, screen, new Hello(SenderId, "Pixel", Token, ProtocolVersion.Current), Role.Sender);
        Exchange(phone, screen, new HelloAck(ReceiverId, true), Role.Receiver);
        Assert.Equal(SessionState.Session, phone.State);
        Exchange(phone, screen, new AimStart(), Role.Sender);
        Exchange(phone, screen, new Handoff(Video, 462_000, true), Role.Sender);
        Exchange(phone, screen, new HandoffAck(true), Role.Receiver);
        Assert.Equal(SessionState.HandoffActive, phone.State);
        Exchange(phone, screen, new Control(ControlAction.Pause), Role.Sender);
        Exchange(phone, screen, new PlaybackState(462_500, false, false), Role.Receiver);
        Exchange(phone, screen, new Control(ControlAction.Seek, 600_000), Role.Sender);
        Exchange(phone, screen, new PullbackRequest(), Role.Sender);
        Exchange(phone, screen, new Pullback(600_000, false), Role.Receiver);
        Assert.Equal(SessionState.Session, phone.State);
    }

    [Fact]
    public void Ended_state_returns_to_session_and_late_state_is_tolerated()
    {
        var phone = InHandoff();

        phone.Incoming(new PlaybackState(1000, false, true));
        Assert.Equal(SessionState.Session, phone.State);

        phone.Incoming(new PlaybackState(1000, false, true));
        Assert.Equal(SessionState.Session, phone.State);
    }

    [Fact]
    public void Expired_code_returns_to_connected()
    {
        var screen = new ProtocolSession(Role.Receiver);
        screen.Incoming(new PairRequest(SenderId, "Pixel"));

        screen.Outgoing(PairResult.Failure(PairFailure.CodeExpired));

        Assert.Equal(SessionState.Connected, screen.State);
    }

    [Fact]
    public void Refused_hello_stays_connected()
    {
        var phone = new ProtocolSession(Role.Sender);
        phone.Outgoing(new Hello(SenderId, "Pixel", Token, 1));

        phone.Incoming(new HelloAck(ReceiverId, false, ErrorCode.NotPaired));

        Assert.Equal(SessionState.Connected, phone.State);
    }

    [Fact]
    public void Session_messages_before_hello_are_not_paired()
    {
        var screen = new ProtocolSession(Role.Receiver);

        var error = Assert.Throws<ProtocolException>(() => screen.Incoming(new Handoff(Video, 0, true)));

        Assert.Equal(ErrorCode.NotPaired, error.Code);
    }

    [Fact]
    public void Control_without_active_handoff_is_bad_message()
    {
        var screen = new ProtocolSession(Role.Receiver);
        screen.Incoming(new Hello(SenderId, "Pixel", Token, 1));
        screen.Outgoing(new HelloAck(ReceiverId, true));

        var error = Assert.Throws<ProtocolException>(() => screen.Incoming(new Control(ControlAction.Play)));

        Assert.Equal(ErrorCode.BadMessage, error.Code);
    }

    [Fact]
    public void Receiver_only_messages_cannot_come_from_the_sender()
    {
        var screen = new ProtocolSession(Role.Receiver);

        Assert.Throws<ProtocolException>(() => screen.Incoming(new HelloAck(ReceiverId, true)));
        Assert.Throws<InvalidOperationException>(() => screen.Outgoing(new Hello(SenderId, "x", Token, 1)));
    }

    [Fact]
    public void Keepalive_and_errors_are_allowed_everywhere()
    {
        foreach (var state in Enum.GetValues<SessionState>())
        {
            foreach (var role in Enum.GetValues<Role>())
            {
                Assert.True(ProtocolSession.IsAllowed(new Ping(), role, state));
                Assert.True(ProtocolSession.IsAllowed(new Pong(), role, state));
                Assert.True(ProtocolSession.IsAllowed(new ErrorMessage(ErrorCode.Busy, "busy"), role, state));
            }
        }
    }

    [Fact]
    public void Aim_ack_answers_a_lock_only_once_in_a_session()
    {
        var phone = new ProtocolSession(Role.Sender);
        Assert.Throws<ProtocolException>(() => phone.Incoming(new AimAck(true)));

        phone.Outgoing(new Hello(SenderId, "Pixel", Token, 1));
        phone.Incoming(new HelloAck(ReceiverId, true));
        phone.Outgoing(new AimStart("Qm9vVGhlU2NyZWVu"));
        phone.Incoming(new AimAck(true));

        Assert.Equal(SessionState.Session, phone.State);
    }

    [Fact]
    public void Version_check()
    {
        Assert.True(ProtocolVersion.IsSupported(1));
        Assert.False(ProtocolVersion.IsSupported(2));
    }

    private static ProtocolSession InHandoff()
    {
        var phone = new ProtocolSession(Role.Sender);
        phone.Outgoing(new Hello(SenderId, "Pixel", Token, 1));
        phone.Incoming(new HelloAck(ReceiverId, true));
        phone.Outgoing(new Handoff(Video, 0, true));
        phone.Incoming(new HandoffAck(true));
        return phone;
    }
}
