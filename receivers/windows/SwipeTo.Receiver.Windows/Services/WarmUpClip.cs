using Windows.Media.Editing;
using Windows.Media.MediaProperties;
using Windows.Storage;

namespace SwipeTo.Receiver.Windows.Services;

/// <summary>A one-second, tiny H.264 clip rendered once into the data directory, used to warm up playback.</summary>
internal static class WarmUpClip
{
    public static async Task<string> GetAsync(string dataDir)
    {
        var path = Path.Combine(dataDir, "warmup.mp4");
        if (File.Exists(path) && new FileInfo(path).Length > 0) return path;

        var composition = new MediaComposition();
        composition.Clips.Add(MediaClip.CreateFromColor(global::Windows.UI.Color.FromArgb(255, 0, 0, 0), TimeSpan.FromSeconds(1)));
        Directory.CreateDirectory(dataDir);
        await File.WriteAllBytesAsync(path, []);
        var file = await StorageFile.GetFileFromPathAsync(path);
        var profile = MediaEncodingProfile.CreateMp4(VideoEncodingQuality.Qvga);
        var result = await composition.RenderToFileAsync(file, MediaTrimmingPreference.Fast, profile);
        if (result != global::Windows.Media.Transcoding.TranscodeFailureReason.None)
        {
            File.Delete(path);
            throw new InvalidOperationException($"Could not render the warm-up clip: {result}");
        }
        return path;
    }
}
