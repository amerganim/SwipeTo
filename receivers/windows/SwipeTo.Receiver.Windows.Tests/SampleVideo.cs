using Windows.Media.Editing;
using Windows.Media.MediaProperties;
using Windows.Storage;
using Windows.UI;

namespace SwipeTo.Receiver.Windows.Tests;

/// <summary>
/// A 3-minute H.264 MP4, a new colour every 10 s, rendered once with Windows media APIs and cached
/// (same output as tools/make-sample-video).
/// </summary>
internal static class SampleVideo
{
    public const long DurationMs = 180_000;

    private static readonly SemaphoreSlim Gate = new(1, 1);

    public static async Task<string> GetAsync()
    {
        var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SwipeTo", "sample", "swipeto-sample.mp4");
        await Gate.WaitAsync();
        try
        {
            if (File.Exists(path) && new FileInfo(path).Length > 0) return path;

            Color[] palette =
            [
                Color.FromArgb(255, 220, 50, 47), Color.FromArgb(255, 38, 139, 210), Color.FromArgb(255, 133, 153, 0),
                Color.FromArgb(255, 181, 137, 0), Color.FromArgb(255, 108, 113, 196), Color.FromArgb(255, 42, 161, 152),
            ];
            var composition = new MediaComposition();
            for (var i = 0; i < DurationMs / 10_000; i++)
            {
                composition.Clips.Add(MediaClip.CreateFromColor(palette[i % palette.Length], TimeSpan.FromSeconds(10)));
            }
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            await File.WriteAllBytesAsync(path, []);
            var file = await StorageFile.GetFileFromPathAsync(path);
            await composition.RenderToFileAsync(file, MediaTrimmingPreference.Fast, MediaEncodingProfile.CreateMp4(VideoEncodingQuality.HD720p));
            return path;
        }
        finally
        {
            Gate.Release();
        }
    }
}
