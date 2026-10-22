using System.Net;
using System.Net.Http.Headers;

namespace SwipeTo.Net.Receiver;

/// <summary>
/// A read-only, seekable <see cref="Stream"/> over a URL served with HTTP Range support (the sender's
/// media server). Data is fetched in fixed-size blocks on demand, the next block is read ahead in the
/// background, and recent blocks are kept in memory.
/// <para>
/// Players that open a URL themselves tend to buffer seconds of media before starting; handing them
/// this stream instead lets playback start as soon as the first frames' bytes are here.
/// </para>
/// </summary>
public sealed class HttpRangeStream : Stream
{
    public const int DefaultBlockSize = 512 * 1024;
    private const int MaxCachedBlocks = 64; // 32 MiB at the default block size

    /// <summary>
    /// Blocks fetched ahead of the one being read. More in flight did not start playback sooner from a
    /// Galaxy A15 (the player just buffered more first); its Wi-Fi delivers about 2.4 MB/s here.
    /// </summary>
    public const int ReadAheadBlocks = 1;

    private readonly HttpClient _http;
    private readonly Uri _url;
    private readonly int _blockSize;
    private readonly Dictionary<long, Task<byte[]>> _blocks = [];
    private readonly LinkedList<long> _recent = new();
    private readonly object _gate = new();
    private readonly CancellationTokenSource _disposed = new();
    private long _position;
    private long _bytesFetched;
    private int _requests = 1;

    private HttpRangeStream(HttpClient http, Uri url, long length, int blockSize, byte[] firstBlock)
    {
        _http = http;
        _url = url;
        Length = length;
        _blockSize = blockSize;
        _bytesFetched = firstBlock.Length;
        Remember(0, Task.FromResult(firstBlock));
    }

    /// <summary>Opens the stream: reads the first block, which also tells us the total length.</summary>
    public static async Task<HttpRangeStream> OpenAsync(HttpClient http, Uri url, int blockSize = DefaultBlockSize, CancellationToken ct = default)
    {
        using var response = await GetRangeAsync(http, url, 0, blockSize - 1, ct);
        var length = response.Content.Headers.ContentRange?.Length
            ?? (response.StatusCode == HttpStatusCode.OK ? response.Content.Headers.ContentLength : null)
            ?? throw new HttpRequestException("The media server did not report the file size");
        var first = await response.Content.ReadAsByteArrayAsync(ct);
        return new HttpRangeStream(http, url, length, blockSize, first);
    }

    /// <summary>Starts fetching the block holding <paramref name="offset"/> (and the next) without waiting.</summary>
    public void Prefetch(long offset)
    {
        if (offset < 0 || offset >= Length) return;
        _ = Block(offset / _blockSize);
        _ = Block(offset / _blockSize + 1);
    }

    /// <summary>Bytes downloaded so far, including read-ahead; for diagnosing slow starts.</summary>
    public long BytesFetched => Interlocked.Read(ref _bytesFetched);

    /// <summary>Range requests made so far.</summary>
    public int Requests => Volatile.Read(ref _requests);

    public override bool CanRead => true;
    public override bool CanSeek => true;
    public override bool CanWrite => false;
    public override long Length { get; }

    public override long Position
    {
        get => Interlocked.Read(ref _position);
        set => Seek(value, SeekOrigin.Begin);
    }

    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
    {
        var position = Position;
        if (position >= Length || buffer.Length == 0) return 0;

        var index = position / _blockSize;
        var block = await Block(index).WaitAsync(ct);
        var offsetInBlock = (int)(position - index * _blockSize);
        var count = Math.Min(buffer.Length, block.Length - offsetInBlock);
        block.AsMemory(offsetInBlock, count).CopyTo(buffer);
        Interlocked.Exchange(ref _position, position + count);

        // Read ahead: players read sequentially most of the time.
        ReadAhead(index);
        return count;
    }

    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
        ReadAsync(buffer.AsMemory(offset, count), ct).AsTask();

    public override int Read(byte[] buffer, int offset, int count) => ReadAsync(buffer.AsMemory(offset, count)).AsTask().GetAwaiter().GetResult();

    public override long Seek(long offset, SeekOrigin origin)
    {
        var target = origin switch
        {
            SeekOrigin.Begin => offset,
            SeekOrigin.Current => Position + offset,
            SeekOrigin.End => Length + offset,
            _ => throw new ArgumentOutOfRangeException(nameof(origin)),
        };
        if (target < 0) throw new IOException("Seek before the start of the stream");
        Interlocked.Exchange(ref _position, target);
        return target;
    }

    public override void Flush()
    {
    }

    public override void SetLength(long value) => throw new NotSupportedException();

    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing && !_disposed.IsCancellationRequested)
        {
            _disposed.Cancel();
            _disposed.Dispose();
        }
        base.Dispose(disposing);
    }

    private void ReadAhead(long index)
    {
        for (var next = index + 1; next <= index + ReadAheadBlocks && next * _blockSize < Length; next++) _ = Block(next);
    }

    private Task<byte[]> Block(long index)
    {
        lock (_gate)
        {
            if (_blocks.TryGetValue(index, out var existing) && !existing.IsFaulted && !existing.IsCanceled)
            {
                _recent.Remove(index);
                _recent.AddFirst(index);
                return existing;
            }
            var from = index * _blockSize;
            var to = Math.Min(from + _blockSize, Length) - 1;
            var fetch = FetchAsync(from, to);
            Remember(index, fetch);
            return fetch;
        }
    }

    private void Remember(long index, Task<byte[]> block)
    {
        lock (_gate)
        {
            _blocks[index] = block;
            _recent.Remove(index);
            _recent.AddFirst(index);
            while (_recent.Count > MaxCachedBlocks)
            {
                _blocks.Remove(_recent.Last!.Value);
                _recent.RemoveLast();
            }
        }
    }

    private async Task<byte[]> FetchAsync(long from, long to)
    {
        Interlocked.Increment(ref _requests);
        using var response = await GetRangeAsync(_http, _url, from, to, _disposed.Token);
        var data = await response.Content.ReadAsByteArrayAsync(_disposed.Token);
        Interlocked.Add(ref _bytesFetched, data.Length);
        return data.Length == to - from + 1 ? data : throw new IOException($"Expected {to - from + 1} bytes at {from}, got {data.Length}");
    }

    private static async Task<HttpResponseMessage> GetRangeAsync(HttpClient http, Uri url, long from, long to, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Range = new RangeHeaderValue(from, to);
        var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        if (response.StatusCode != HttpStatusCode.PartialContent && !(from == 0 && response.StatusCode == HttpStatusCode.OK))
        {
            response.Dispose();
            throw new HttpRequestException($"Media server answered {(int)response.StatusCode} to a range request");
        }
        return response;
    }
}
