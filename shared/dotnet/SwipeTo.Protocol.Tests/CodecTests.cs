namespace SwipeTo.Protocol.Tests;

public class CodecTests
{
    private const string Id = "3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b";

    [Fact]
    public void New_messages_get_a_lowercase_uuid_and_current_time()
    {
        var before = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var ping = new Ping();

        Assert.Matches("^[0-9a-f-]{36}$", ping.Id);
        Assert.InRange(ping.Ts, before, before + 5000);
    }

    [Fact]
    public void Serialized_messages_use_camelCase_and_omit_absent_optionals()
    {
        var json = MessageCodec.Serialize(new Control(ControlAction.Pause) { Id = Id, Ts = 1 });

        Assert.Equal("{\"type\":\"control\",\"id\":\"" + Id + "\",\"ts\":1,\"action\":\"pause\"}", json);
    }

    [Fact]
    public void Serialization_matches_the_kotlin_library_byte_for_byte()
    {
        // Same message and expected text as the Kotlin CodecTest, so both libraries emit identical JSON.
        var json = MessageCodec.Serialize(
            new Handoff(new MediaInfo(MediaKind.Video, "http://h/m/t/f", "video/mp4", "t", 5), 10, true) { Id = Id, Ts = 1 });

        Assert.Equal(
            "{\"type\":\"handoff\",\"id\":\"" + Id + "\",\"ts\":1,\"media\":{\"kind\":\"video\",\"url\":\"http://h/m/t/f\",\"mime\":\"video/mp4\",\"title\":\"t\",\"durationMs\":5},\"positionMs\":10,\"playing\":true}",
            json);
    }

    [Theory]
    [InlineData("not json")]
    [InlineData("{\"type\":\"ping\",\"id\":\"" + Id + "\",\"ts\":1.5}")]
    [InlineData("{\"type\":\"ping\",\"id\":\"" + Id + "\"}")]
    public void Malformed_input_is_bad_message(string json)
    {
        Assert.Equal(ErrorCode.BadMessage, Assert.Throws<ProtocolException>(() => MessageCodec.Parse(json)).Code);
    }

    [Fact]
    public void Rejection_carries_the_message_id_for_replyTo()
    {
        var error = Assert.Throws<ProtocolException>(() =>
            MessageCodec.Parse("{\"type\":\"control\",\"id\":\"" + Id + "\",\"ts\":1,\"action\":\"seek\"}"));

        Assert.Equal(Id, error.ToErrorMessage().ReplyTo);
    }

    [Fact]
    public void Oversized_messages_are_rejected()
    {
        var json = "{\"type\":\"error\",\"id\":\"" + Id + "\",\"ts\":1,\"code\":\"busy\",\"message\":\"" + new string('x', 70_000) + "\"}";

        Assert.Throws<ProtocolException>(() => MessageCodec.Parse(json));
    }

    [Fact]
    public void Name_length_counts_code_points()
    {
        // 64 emoji = 128 UTF-16 units but 64 code points: allowed.
        var name = string.Concat(Enumerable.Repeat("\U0001F600", 64));
        var json = MessageCodec.Serialize(new PairRequest("a1b2c3d4-e5f6-4789-8abc-def012345678", name));

        Assert.Equal(name, ((PairRequest)MessageCodec.Parse(json)).SenderName);
    }

    [Fact]
    public void Wire_enum_names_are_snake_case()
    {
        Assert.Equal("unsupported_media", WireEnum.ToWire(ErrorCode.UnsupportedMedia));
        Assert.Equal("too_many_attempts", WireEnum.ToWire(PairFailure.TooManyAttempts));
        Assert.True(WireEnum.TryParse<ErrorCode>("version_mismatch", out var code));
        Assert.Equal(ErrorCode.VersionMismatch, code);
        Assert.False(WireEnum.TryParse<ErrorCode>("VersionMismatch", out _));
    }
}
