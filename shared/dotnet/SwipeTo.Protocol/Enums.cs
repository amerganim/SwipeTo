namespace SwipeTo.Protocol;

public enum ErrorCode
{
    UnsupportedMedia,
    NotPaired,
    BadMessage,
    Busy,
    VersionMismatch,
}

public enum PairFailure
{
    WrongCode,
    CodeExpired,
    TooManyAttempts,
    NotPairing,
}

public enum MediaKind
{
    Video,
    Image,
}

public enum ControlAction
{
    Play,
    Pause,
    Seek,
    Stop,
}

/// <summary>Maps enum members to their snake_case wire names (<c>UnsupportedMedia</c> ⇄ <c>unsupported_media</c>).</summary>
public static class WireEnum
{
    public static string ToWire<T>(T value) where T : struct, Enum => Names<T>.ToWire[value];

    public static bool TryParse<T>(string wire, out T value) where T : struct, Enum =>
        Names<T>.FromWire.TryGetValue(wire, out value);

    private static class Names<T> where T : struct, Enum
    {
        public static readonly Dictionary<T, string> ToWire =
            Enum.GetValues<T>().ToDictionary(v => v, v => SnakeCase(v.ToString()));

        public static readonly Dictionary<string, T> FromWire =
            ToWire.ToDictionary(kv => kv.Value, kv => kv.Key, StringComparer.Ordinal);
    }

    private static string SnakeCase(string pascal) =>
        string.Concat(pascal.Select((c, i) => char.IsUpper(c) && i > 0 ? "_" + char.ToLowerInvariant(c) : char.ToLowerInvariant(c).ToString()));
}
