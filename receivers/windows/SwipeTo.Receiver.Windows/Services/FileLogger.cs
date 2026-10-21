using System.Collections.Concurrent;
using System.Globalization;
using Microsoft.Extensions.Logging;

namespace SwipeTo.Receiver.Windows.Services;

/// <summary>Appends log lines to <c>logs\receiver-yyyyMMdd.log</c>; keeps the last 7 days.</summary>
public sealed class FileLoggerProvider : ILoggerProvider
{
    private readonly string _directory;
    private readonly BlockingCollection<string> _lines = new(boundedCapacity: 10_000);
    private readonly Thread _writer;

    public FileLoggerProvider(string directory)
    {
        _directory = directory;
        Directory.CreateDirectory(directory);
        foreach (var old in Directory.GetFiles(directory, "receiver-*.log").Where(f => File.GetLastWriteTimeUtc(f) < DateTime.UtcNow.AddDays(-7)))
        {
            File.Delete(old);
        }
        _writer = new Thread(WriteLoop) { IsBackground = true, Name = "SwipeTo log writer" };
        _writer.Start();
    }

    public string CurrentFile => Path.Combine(_directory, $"receiver-{DateTime.Now:yyyyMMdd}.log");

    public ILogger CreateLogger(string categoryName) => new FileLogger(this, categoryName[(categoryName.LastIndexOf('.') + 1)..]);

    public void Dispose()
    {
        _lines.CompleteAdding();
        _writer.Join(TimeSpan.FromSeconds(2));
    }

    private void Enqueue(string line) => _lines.TryAdd(line);

    private void WriteLoop()
    {
        foreach (var line in _lines.GetConsumingEnumerable())
        {
            try
            {
                File.AppendAllText(CurrentFile, line + Environment.NewLine);
            }
            catch (IOException)
            {
                // Another process holds the file for a moment; dropping a line beats crashing the receiver.
            }
        }
    }

    private sealed class FileLogger(FileLoggerProvider provider, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Information;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel)) return;
            var line = string.Create(CultureInfo.InvariantCulture,
                $"{DateTime.Now:HH:mm:ss.fff} {logLevel.ToString()[..4].ToUpperInvariant()} {category}: {formatter(state, exception)}");
            provider.Enqueue(exception is null ? line : line + Environment.NewLine + exception);
        }
    }
}
