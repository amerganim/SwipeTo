using Windows.Media.Editing;
using Windows.Media.MediaProperties;
using Windows.Storage;
using Windows.UI;

// Usage: SwipeTo.MakeSampleVideo <output.mp4> [minutes]
// A new colour every 10 seconds, so a seek is visible at a glance on the receiver.
var output = Path.GetFullPath(args.Length > 0 ? args[0] : "swipeto-sample.mp4");
var minutes = args.Length > 1 ? int.Parse(args[1]) : 3;

Color[] palette =
[
    Color.FromArgb(255, 220, 50, 47), Color.FromArgb(255, 38, 139, 210), Color.FromArgb(255, 133, 153, 0),
    Color.FromArgb(255, 181, 137, 0), Color.FromArgb(255, 108, 113, 196), Color.FromArgb(255, 42, 161, 152),
];

var composition = new MediaComposition();
for (var i = 0; i < minutes * 6; i++)
{
    composition.Clips.Add(MediaClip.CreateFromColor(palette[i % palette.Length], TimeSpan.FromSeconds(10)));
}

Directory.CreateDirectory(Path.GetDirectoryName(output)!);
if (File.Exists(output)) File.Delete(output);
await File.WriteAllBytesAsync(output, []);
var file = await StorageFile.GetFileFromPathAsync(output);
var profile = MediaEncodingProfile.CreateMp4(VideoEncodingQuality.HD720p);
var result = await composition.RenderToFileAsync(file, MediaTrimmingPreference.Fast, profile);
Console.WriteLine(result == Windows.Media.Transcoding.TranscodeFailureReason.None
    ? $"Wrote {output} ({new FileInfo(output).Length / 1024} KiB, {minutes} min)"
    : $"Render failed: {result}");
return result == Windows.Media.Transcoding.TranscodeFailureReason.None ? 0 : 1;
