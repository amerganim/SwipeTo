using SwipeTo.Protocol;

namespace SwipeTo.Net.Receiver;

public readonly record struct PlaybackSnapshot(long PositionMs, bool Playing, bool Ended);

/// <summary>
/// The thing that actually plays media on a receiver: a simulated clock in the mock receiver, a
/// MediaPlayerElement on Windows. Implementations marshal to their own UI thread as needed.
/// </summary>
public interface IPlaybackTarget
{
    /// <summary>Whether this receiver can play the kind and MIME type at all (checked before <c>handoff_ack</c>).</summary>
    bool CanPlay(MediaInfo media);

    /// <summary>
    /// Opens the media, seeks to <paramref name="positionMs"/>, and plays or stays paused. Throws if it
    /// cannot load. Uses a matching prefetch if there is one. <paramref name="transition"/> says which
    /// way an album moved, for a slide-in.
    /// </summary>
    Task LoadAsync(MediaInfo media, long positionMs, bool playing, Transition? transition, CancellationToken ct);

    /// <summary>The URL being played or shown, or null; a handoff of the same URL re-attaches instead of reloading.</summary>
    string? CurrentUrl { get; }

    /// <summary>
    /// Starts opening <paramref name="media"/> at <paramref name="positionMs"/>, paused and unseen, so a
    /// following <see cref="LoadAsync"/> of the same URL starts at once (docs/protocol.md, 5.3). Calling it
    /// again for the same URL only moves the position. Best effort: never throws.
    /// </summary>
    void Prefetch(MediaInfo media, long positionMs) { }

    /// <summary>Drops a prefetch that will not be used.</summary>
    void DiscardPrefetch() { }

    void Play();
    void Pause();
    void Seek(long positionMs);

    /// <summary>Ends playback and closes the player.</summary>
    void Stop();

    PlaybackSnapshot Snapshot { get; }

    /// <summary>Raised on any change: play, pause, seek done, ended, or closed by the viewer (Ended = true).</summary>
    event Action? Changed;
}

/// <summary>What the receiver shows on screen besides the media.</summary>
public interface IReceiverUi
{
    void ShowPairingCode(string code, string senderName);
    void HidePairingCode();

    /// <summary>
    /// Show (or update) the edge glow and a QR marker encoding <paramref name="markerPayload"/>.
    /// Called again every rotation with a new payload; <paramref name="locked"/> once a phone has
    /// proved it read the marker.
    /// </summary>
    void ShowAim(string markerPayload, bool locked);
    void HideAim();
}

public sealed class ReceiverOptions
{
    public const int DefaultPort = 48620;

    public required string ReceiverId { get; init; }
    public required string Name { get; init; }
    public string Platform { get; init; } = "windows";
    public string Caps { get; init; } = "video,image";

    /// <summary>0 picks a free port.</summary>
    public int Port { get; init; } = DefaultPort;

    public bool Advertise { get; init; } = true;
    public TimeSpan PairingCodeLifetime { get; init; } = TimeSpan.FromSeconds(60);
    public int PairingAttempts { get; init; } = 3;
    public TimeSpan PairRequestInterval { get; init; } = TimeSpan.FromSeconds(5);
    public TimeSpan AimTimeout { get; init; } = TimeSpan.FromSeconds(10);
    public TimeSpan MarkerRotation { get; init; } = TimeSpan.FromSeconds(2);
    public TimeSpan StateInterval { get; init; } = TimeSpan.FromSeconds(1);

    /// <summary>For automated tests only: always use this pairing code.</summary>
    public string? FixedPairingCode { get; init; }
}
