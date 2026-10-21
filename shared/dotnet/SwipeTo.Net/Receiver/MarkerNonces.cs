namespace SwipeTo.Net.Receiver;

/// <summary>
/// The nonce in this screen's QR marker (docs/protocol.md, section 5.3). It is replaced every 2 s
/// while the marker is shown; a phone's read is accepted if it matches the current nonce or the one
/// just before it, so a read that crosses a rotation still counts but an old photo does not.
/// </summary>
public sealed class MarkerNonces
{
    private readonly object _gate = new();
    private string _current = Secrets.Token(12);
    private string? _previous;

    public string Current
    {
        get { lock (_gate) return _current; }
    }

    public string Payload(string receiverId) => $"swipeto:{receiverId}:{Current}";

    public void Rotate()
    {
        lock (_gate)
        {
            _previous = _current;
            _current = Secrets.Token(12);
        }
    }

    public bool IsValid(string nonce)
    {
        lock (_gate)
        {
            return Secrets.FixedTimeEquals(nonce, _current) || (_previous is not null && Secrets.FixedTimeEquals(nonce, _previous));
        }
    }
}
