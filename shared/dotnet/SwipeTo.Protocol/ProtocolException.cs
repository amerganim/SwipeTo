namespace SwipeTo.Protocol;

/// <summary>A message or session rule was broken. <see cref="Code"/> is what to send back in an <c>error</c>.</summary>
public sealed class ProtocolException(ErrorCode code, string message, string? messageId = null) : Exception(message)
{
    public ErrorCode Code { get; } = code;

    /// <summary>The offending message's <c>id</c>, when it could be read, for <c>replyTo</c>.</summary>
    public string? MessageId { get; } = messageId;

    public ErrorMessage ToErrorMessage() => new(Code, Message.Length <= 500 ? Message : Message[..500]) { ReplyTo = MessageId };
}
