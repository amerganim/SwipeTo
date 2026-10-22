using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace SwipeTo.Protocol;

/// <summary>
/// Strict JSON codec for v1 messages. <see cref="Parse(string)"/> enforces the same rules as
/// docs/schemas: required fields, types, patterns, enums, conditional fields, and no unknown fields.
/// </summary>
public static partial class MessageCodec
{
    public const int MaxMessageBytes = 64 * 1024;

    public static Message Parse(string json) => Parse(Encoding.UTF8.GetBytes(json));

    public static Message Parse(ReadOnlySpan<byte> utf8)
    {
        if (utf8.Length > MaxMessageBytes) throw Bad("Message is larger than 64 KiB");

        JsonDocument doc;
        try
        {
            var reader = new Utf8JsonReader(utf8);
            doc = JsonDocument.ParseValue(ref reader);
        }
        catch (JsonException)
        {
            throw Bad("Not valid JSON");
        }

        using (doc)
        {
            if (doc.RootElement.ValueKind != JsonValueKind.Object) throw Bad("A message must be a JSON object");

            // Read the id first so a later failure can still be correlated with replyTo.
            string? idForReply = doc.RootElement.TryGetProperty("id", out var rawId) && rawId.ValueKind == JsonValueKind.String
                && UuidPattern().IsMatch(rawId.GetString()!) ? rawId.GetString() : null;
            try
            {
                return ParseObject(new Fields(doc.RootElement, "message"));
            }
            catch (ProtocolException e) when (e.MessageId is null && idForReply is not null)
            {
                throw new ProtocolException(e.Code, e.Message, idForReply);
            }
        }
    }

    private static Message ParseObject(Fields f)
    {
        var type = f.String("type");
        var id = f.Uuid("id");
        var ts = f.Integer("ts");
        var replyTo = f.OptionalUuid("replyTo");

        Message message = type switch
        {
            MessageTypes.PairRequest => new PairRequest(f.Uuid("senderId"), f.DeviceName("senderName")),
            MessageTypes.PairConfirm => new PairConfirm(f.Matching("code", PairCodePattern())),
            MessageTypes.PairResult => ParsePairResult(f),
            MessageTypes.Hello => new Hello(
                f.Uuid("senderId"), f.DeviceName("senderName"), f.Matching("trustToken", TrustTokenPattern()), ParseVersion(f)),
            MessageTypes.HelloAck => new HelloAck(f.Uuid("receiverId"), f.Bool("accepted"), f.OptionalEnum<ErrorCode>("reason")),
            MessageTypes.AimStart => new AimStart(f.OptionalMatching("nonce", MarkerNoncePattern()), ParsePrefetch(f.OptionalObject("prefetch"))),
            MessageTypes.AimAck => new AimAck(f.Bool("locked")),
            MessageTypes.AimEnd => new AimEnd(),
            MessageTypes.Prepare => new Prepare(ParseMedia(f.Object("media")), f.Integer("positionMs")),
            MessageTypes.Handoff => new Handoff(ParseMedia(f.Object("media")), f.Integer("positionMs"), f.Bool("playing"), f.OptionalEnum<Transition>("transition")),
            MessageTypes.HandoffAck => new HandoffAck(f.Bool("accepted"), f.OptionalEnum<ErrorCode>("reason")),
            MessageTypes.Control => new Control(f.Enum<ControlAction>("action"), f.OptionalInteger("positionMs")),
            MessageTypes.State => new PlaybackState(f.Integer("positionMs"), f.Bool("playing"), f.Bool("ended"), f.OptionalBool("buffering") ?? false),
            MessageTypes.PullbackRequest => new PullbackRequest(),
            MessageTypes.Pullback => new Pullback(f.Integer("positionMs"), f.Bool("playing")),
            MessageTypes.Ping => new Ping(),
            MessageTypes.Pong => new Pong(),
            MessageTypes.Error => new ErrorMessage(f.Enum<ErrorCode>("code"), f.Text("message", 0, 500)),
            _ => throw Bad($"Unknown message type '{type}'"),
        };

        switch (message)
        {
            case HelloAck { Accepted: false, Reason: null }:
            case HandoffAck { Accepted: false, Reason: null }:
                throw Bad($"{type}: reason is required when not accepted");
            case Control { Action: ControlAction.Seek, PositionMs: null }:
                throw Bad("control: positionMs is required for seek");
        }

        f.RejectUnknown();
        return message with { Id = id, Ts = ts, ReplyTo = replyTo };
    }

    private static int ParseVersion(Fields f)
    {
        var version = f.Integer("protocolVersion");
        if (version is < 1 or > int.MaxValue) throw Bad("protocolVersion must be an integer >= 1");
        return (int)version;
    }

    private static PairResult ParsePairResult(Fields f)
    {
        var ok = f.Bool("ok");
        var token = f.OptionalMatching("trustToken", TrustTokenPattern());
        var reason = f.OptionalEnum<PairFailure>("reason");
        return (ok, token, reason) switch
        {
            (true, not null, null) => PairResult.Success(token),
            (false, null, not null) => PairResult.Failure(reason.Value),
            (true, _, _) => throw Bad("pair_result: ok=true needs trustToken and no reason"),
            _ => throw Bad("pair_result: ok=false needs reason and no trustToken"),
        };
    }

    private static Prefetch? ParsePrefetch(Fields? p)
    {
        if (p is null) return null;
        var prefetch = new Prefetch(ParseMedia(p.Object("media")), p.Integer("positionMs"));
        p.RejectUnknown();
        return prefetch;
    }

    private static MediaInfo ParseMedia(Fields m)
    {
        var media = new MediaInfo(
            m.Enum<MediaKind>("kind"),
            m.Matching("url", UrlPattern(), maxLength: 2048),
            m.Matching("mime", MimePattern()),
            m.Text("title", 0, 200),
            m.OptionalInteger("durationMs"));
        m.RejectUnknown();
        return media;
    }

    public static string Serialize(Message message)
    {
        using var buffer = new MemoryStream();
        using (var w = new Utf8JsonWriter(buffer))
        {
            w.WriteStartObject();
            w.WriteString("type", message.Type);
            w.WriteString("id", message.Id);
            w.WriteNumber("ts", message.Ts);
            if (message.ReplyTo is not null) w.WriteString("replyTo", message.ReplyTo);
            WritePayload(w, message);
            w.WriteEndObject();
        }
        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    private static void WritePayload(Utf8JsonWriter w, Message message)
    {
        switch (message)
        {
            case PairRequest m:
                w.WriteString("senderId", m.SenderId);
                w.WriteString("senderName", m.SenderName);
                break;
            case PairConfirm m:
                w.WriteString("code", m.Code);
                break;
            case PairResult m:
                w.WriteBoolean("ok", m.Ok);
                if (m.TrustToken is not null) w.WriteString("trustToken", m.TrustToken);
                if (m.Reason is { } failure) w.WriteString("reason", WireEnum.ToWire(failure));
                break;
            case Hello m:
                w.WriteString("senderId", m.SenderId);
                w.WriteString("senderName", m.SenderName);
                w.WriteString("trustToken", m.TrustToken);
                w.WriteNumber("protocolVersion", m.ProtocolVersion);
                break;
            case HelloAck m:
                w.WriteString("receiverId", m.ReceiverId);
                w.WriteBoolean("accepted", m.Accepted);
                if (m.Reason is { } helloReason) w.WriteString("reason", WireEnum.ToWire(helloReason));
                break;
            case Handoff m:
                WriteMedia(w, m.Media);
                w.WriteNumber("positionMs", m.PositionMs);
                w.WriteBoolean("playing", m.Playing);
                if (m.Transition is { } transition) w.WriteString("transition", WireEnum.ToWire(transition));
                break;
            case HandoffAck m:
                w.WriteBoolean("accepted", m.Accepted);
                if (m.Reason is { } handoffReason) w.WriteString("reason", WireEnum.ToWire(handoffReason));
                break;
            case Control m:
                w.WriteString("action", WireEnum.ToWire(m.Action));
                if (m.PositionMs is { } position) w.WriteNumber("positionMs", position);
                break;
            case PlaybackState m:
                w.WriteNumber("positionMs", m.PositionMs);
                w.WriteBoolean("playing", m.Playing);
                w.WriteBoolean("ended", m.Ended);
                if (m.Buffering) w.WriteBoolean("buffering", true);
                break;
            case Pullback m:
                w.WriteNumber("positionMs", m.PositionMs);
                w.WriteBoolean("playing", m.Playing);
                break;
            case ErrorMessage m:
                w.WriteString("code", WireEnum.ToWire(m.Code));
                w.WriteString("message", m.Description);
                break;
            case AimStart m:
                if (m.Nonce is not null) w.WriteString("nonce", m.Nonce);
                if (m.Prefetch is { } prefetch)
                {
                    w.WriteStartObject("prefetch");
                    WriteMedia(w, prefetch.Media);
                    w.WriteNumber("positionMs", prefetch.PositionMs);
                    w.WriteEndObject();
                }
                break;
            case AimAck m:
                w.WriteBoolean("locked", m.Locked);
                break;
            case Prepare m:
                WriteMedia(w, m.Media);
                w.WriteNumber("positionMs", m.PositionMs);
                break;
            case AimEnd or PullbackRequest or Ping or Pong:
                break;
            default:
                throw new ArgumentException($"Unknown message record {message.GetType().Name}", nameof(message));
        }
    }

    private static void WriteMedia(Utf8JsonWriter w, MediaInfo media)
    {
        w.WriteStartObject("media");
        w.WriteString("kind", WireEnum.ToWire(media.Kind));
        w.WriteString("url", media.Url);
        w.WriteString("mime", media.Mime);
        w.WriteString("title", media.Title);
        if (media.DurationMs is { } duration) w.WriteNumber("durationMs", duration);
        w.WriteEndObject();
    }

    internal static ProtocolException Bad(string reason) => new(ErrorCode.BadMessage, reason);

    [GeneratedRegex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")]
    internal static partial Regex UuidPattern();

    [GeneratedRegex("^[A-Za-z0-9_-]{22,128}$")]
    internal static partial Regex TrustTokenPattern();

    [GeneratedRegex("^[A-Za-z0-9_-]{16,64}$")]
    internal static partial Regex MarkerNoncePattern();

    [GeneratedRegex("^[0-9]{6}$")]
    internal static partial Regex PairCodePattern();

    [GeneratedRegex(@"^https?://[^\s]+$")]
    internal static partial Regex UrlPattern();

    [GeneratedRegex("^(video|image)/[A-Za-z0-9.+-]+$")]
    internal static partial Regex MimePattern();

    /// <summary>Reads fields from one JSON object and remembers which were read, to reject the rest.</summary>
    private sealed class Fields(JsonElement obj, string where)
    {
        private readonly HashSet<string> _read = new(StringComparer.Ordinal);

        private JsonElement? Get(string name)
        {
            _read.Add(name);
            return obj.TryGetProperty(name, out var value) ? value : null;
        }

        private JsonElement Required(string name) => Get(name) ?? throw Bad($"{where}: missing field '{name}'");

        public string String(string name)
        {
            var v = Required(name);
            return v.ValueKind == JsonValueKind.String ? v.GetString()! : throw Bad($"{where}: '{name}' must be a string");
        }

        public string Text(string name, int minLength, int maxLength)
        {
            var s = String(name);
            var length = CodePoints(s);
            return length >= minLength && length <= maxLength
                ? s
                : throw Bad($"{where}: '{name}' must be {minLength}–{maxLength} characters");
        }

        public string DeviceName(string name) => Text(name, 1, 64);

        public string Matching(string name, Regex pattern, int maxLength = int.MaxValue)
        {
            var s = String(name);
            return s.Length <= maxLength && pattern.IsMatch(s) ? s : throw Bad($"{where}: '{name}' has an invalid format");
        }

        public string? OptionalMatching(string name, Regex pattern) => Get(name) is null ? null : Matching(name, pattern);

        public string Uuid(string name) => Matching(name, UuidPattern());

        public string? OptionalUuid(string name) => OptionalMatching(name, UuidPattern());

        public long Integer(string name)
        {
            var v = Required(name);
            if (v.ValueKind != JsonValueKind.Number || !v.TryGetInt64(out var n)) throw Bad($"{where}: '{name}' must be an integer");
            return n >= 0 ? n : throw Bad($"{where}: '{name}' must not be negative");
        }

        public long? OptionalInteger(string name) => Get(name) is null ? null : Integer(name);

        public bool? OptionalBool(string name) => Get(name) is null ? null : Bool(name);

        public bool Bool(string name) => Required(name).ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => throw Bad($"{where}: '{name}' must be a boolean"),
        };

        public T Enum<T>(string name) where T : struct, Enum =>
            WireEnum.TryParse<T>(String(name), out var value) ? value : throw Bad($"{where}: '{name}' has an unknown value");

        public T? OptionalEnum<T>(string name) where T : struct, Enum => Get(name) is null ? null : Enum<T>(name);

        public Fields? OptionalObject(string name) => Get(name) is null ? null : Object(name);

        public Fields Object(string name)
        {
            var v = Required(name);
            return v.ValueKind == JsonValueKind.Object ? new Fields(v, name) : throw Bad($"{where}: '{name}' must be an object");
        }

        public void RejectUnknown()
        {
            foreach (var property in obj.EnumerateObject())
            {
                if (!_read.Contains(property.Name)) throw Bad($"{where}: unexpected field '{property.Name}'");
            }
        }

        // JSON Schema counts string length in Unicode code points, not UTF-16 units.
        private static int CodePoints(string s) => s.EnumerateRunes().Count();
    }
}
