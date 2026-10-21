namespace SwipeTo.Protocol.Tests;

/// <summary>Locates docs/test-vectors by walking up from the test output directory.</summary>
internal static class TestVectors
{
    public static readonly string Root = Find();

    public static IEnumerable<object[]> Valid() => Files("valid");

    public static IEnumerable<object[]> Invalid() => Files("invalid");

    public static string Read(string kind, string name) => File.ReadAllText(Path.Combine(Root, kind, name + ".json"));

    private static IEnumerable<object[]> Files(string kind) =>
        Directory.GetFiles(Path.Combine(Root, kind), "*.json")
            .Select(path => new object[] { Path.GetFileNameWithoutExtension(path) })
            .OrderBy(row => (string)row[0], StringComparer.Ordinal);

    private static string Find()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "docs", "test-vectors");
            if (Directory.Exists(candidate)) return candidate;
        }
        throw new DirectoryNotFoundException("docs/test-vectors not found above " + AppContext.BaseDirectory);
    }
}
