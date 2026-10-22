using SwipeTo.MockReceiver;

const string usage = """
    SwipeTo mock receiver — acts like a screen, for testing senders.

    Usage: SwipeTo.MockReceiver [options]
      --name <text>        Display name (default "Mock receiver")
      --id <uuid>          Receiver id (default: generated once and saved)
      --port <n>           WebSocket port, 0 = any (default 48620)
      --pair-code <6dig>   Always use this pairing code (testing only)
      --trust-file <path>  Where trusted senders are stored
      --no-mdns            Do not advertise over mDNS
    """;

var options = new MockReceiverOptions();
for (var i = 0; i < args.Length; i++)
{
    string Next() => i + 1 < args.Length ? args[++i] : throw new ArgumentException($"{args[i]} needs a value");
    options = args[i] switch
    {
        "--name" => options with { Name = Next() },
        "--id" => options with { ReceiverId = Next() },
        "--port" => options with { Port = int.Parse(Next()) },
        "--pair-code" => options with { PairingCode = Next() },
        "--trust-file" => options with { TrustFile = Next() },
        "--no-mdns" => options with { Advertise = false },
        "--services" => options with { Services = Next() },
        _ => null!,
    };
    if (options is null)
    {
        Console.WriteLine(usage);
        return args[i] is "-h" or "--help" ? 0 : 2;
    }
}

await using var app = await MockReceiverApp.StartAsync(options);
Console.WriteLine($"Mock receiver \"{options.Name}\" ({app.Host.Options.ReceiverId}) on port {app.Host.Port}. Ctrl+C to quit.");

var quit = new TaskCompletionSource();
Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    quit.TrySetResult();
};
await quit.Task;
return 0;
