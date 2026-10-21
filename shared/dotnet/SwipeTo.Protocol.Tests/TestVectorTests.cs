using System.Text.Json.Nodes;

namespace SwipeTo.Protocol.Tests;

public class TestVectorTests
{
    [Theory]
    [MemberData(nameof(TestVectors.Valid), MemberType = typeof(TestVectors))]
    public void Valid_vectors_parse_and_round_trip(string name)
    {
        var json = TestVectors.Read("valid", name);

        var message = MessageCodec.Parse(json);
        var reserialized = MessageCodec.Serialize(message);

        Assert.Equal(name.Split('-')[0], message.Type);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(json), JsonNode.Parse(reserialized)), reserialized);
        Assert.Equal(message, MessageCodec.Parse(reserialized));
    }

    [Theory]
    [MemberData(nameof(TestVectors.Invalid), MemberType = typeof(TestVectors))]
    public void Invalid_vectors_are_rejected_as_bad_message(string name)
    {
        var json = TestVectors.Read("invalid", name);

        var error = Assert.Throws<ProtocolException>(() => MessageCodec.Parse(json));

        Assert.Equal(ErrorCode.BadMessage, error.Code);
    }

    [Fact]
    public void Every_message_type_has_vectors()
    {
        var types = typeof(MessageTypes).GetFields().Select(f => (string)f.GetValue(null)!).ToList();
        var valid = TestVectors.Valid().Select(r => ((string)r[0]).Split('-')[0]).ToHashSet();
        var invalid = TestVectors.Invalid().Select(r => ((string)r[0]).Split('-')[0]).ToHashSet();

        Assert.Equal(17, types.Count);
        Assert.All(types, t => Assert.Contains(t, valid));
        Assert.All(types, t => Assert.Contains(t, invalid));
    }
}
