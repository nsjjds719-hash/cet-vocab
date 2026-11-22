using System.IO;
using System.Speech.Synthesis;
using System.Text.Json;
using System.Windows;
using Microsoft.Web.WebView2.Core;

namespace Cijian.Windows;

public partial class MainWindow : Window
{
    private static readonly Uri AppPage = new("https://cijian.local/index.html");
    private readonly string storage = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Cijian");
    private SpeechSynthesizer? speaker;
    private Prompt? prompt;
    private int speechRequest;

    public MainWindow()
    {
        InitializeComponent();
        Loaded += InitializeBrowser;
        Closed += (_, _) =>
        {
            speaker?.Dispose();
            Web.Dispose();
        };
    }

    private async void InitializeBrowser(object sender, RoutedEventArgs args)
    {
        try
        {
            string assets = Path.Combine(AppContext.BaseDirectory, "web");
            if (!File.Exists(Path.Combine(assets, "index.html")))
                throw new FileNotFoundException("请将压缩包完整解压，然后打开 Cijian.exe。");
            Directory.CreateDirectory(storage);
            var environment = await CoreWebView2Environment.CreateAsync(
                userDataFolder: Path.Combine(storage, "WebView"));
            await Web.EnsureCoreWebView2Async(environment);
            var browser = Web.CoreWebView2;
            browser.SetVirtualHostNameToFolderMapping(AppPage.Host, assets, CoreWebView2HostResourceAccessKind.Deny);
            browser.Settings.AreDefaultContextMenusEnabled = false;
            browser.Settings.IsStatusBarEnabled = false;
            browser.Settings.AreBrowserAcceleratorKeysEnabled = false;
            browser.NavigationStarting += (_, request) => request.Cancel = request.Uri != AppPage.AbsoluteUri;
            browser.NewWindowRequested += (_, request) => request.Handled = true;
            browser.WebMessageReceived += Receive;
            browser.Navigate(AppPage.AbsoluteUri);
        }
        catch (WebView2RuntimeNotFoundException)
        {
            MessageBox.Show("请安装 Microsoft Edge WebView2 Runtime，然后重新打开词间。", "词间");
            Close();
        }
        catch (Exception error)
        {
            MessageBox.Show("词间启动失败。\n" + error.Message, "词间");
            Close();
        }
    }

    private void Receive(object? sender, CoreWebView2WebMessageReceivedEventArgs message)
    {
        if (message.Source != AppPage.AbsoluteUri) return;
        int id = 0;
        try
        {
            using var json = JsonDocument.Parse(message.WebMessageAsJson);
            var request = json.RootElement;
            id = request.GetProperty("id").GetInt32();
            string progress = Path.Combine(storage, "progress.json");
            switch (request.GetProperty("operation").GetString())
            {
                case "load":
                    Reply(id, File.Exists(progress) ? File.ReadAllText(progress) : null);
                    break;
                case "save":
                    var state = request.GetProperty("value");
                    if (state.ValueKind != JsonValueKind.Object)
                        throw new FormatException("无效的学习记录。");
                    string temporary = progress + ".new";
                    File.WriteAllText(temporary, state.GetRawText());
                    File.Move(temporary, progress, true);
                    Reply(id, true);
                    break;
                case "speak":
                    Speak(id, request.GetProperty("value").GetString() ?? "");
                    break;
                case "stop":
                    StopSpeech();
                    Reply(id, true);
                    break;
            }
        }
        catch (Exception)
        {
            Reply(id, null, "系统操作失败，请重试。");
        }
    }

    private void Speak(int id, string text)
    {
        StopSpeech();
        try
        {
            if (speaker is null)
            {
                speaker = new SpeechSynthesizer();
                speaker.SetOutputToDefaultAudioDevice();
                speaker.SpeakCompleted += (_, result) => Dispatcher.InvokeAsync(() =>
                {
                    if (result.Prompt != prompt) return;
                    prompt = null;
                    Reply(speechRequest, true, result.Error is null ? null : "播放失败，请重试。");
                });
            }
            var voice = speaker.GetInstalledVoices().Where(voice => voice.Enabled)
                .Select(voice => voice.VoiceInfo)
                .Where(voice => voice.Culture.TwoLetterISOLanguageName == "en")
                .OrderByDescending(voice => voice.Culture.Name == "en-US").FirstOrDefault();
            if (voice is null)
            {
                Reply(id, null, "请先在 Windows 的语言设置中安装英语语音。");
                return;
            }
            speaker.SelectVoice(voice.Name);
            speechRequest = id;
            prompt = speaker.SpeakAsync(text);
        }
        catch (Exception)
        {
            Reply(id, null, "英语语音暂不可用，请检查系统语音设置。");
        }
    }

    private void StopSpeech()
    {
        if (prompt is null) return;
        prompt = null;
        speaker?.SpeakAsyncCancelAll();
        Reply(speechRequest, null, "播放已停止。");
    }

    private void Reply(int id, object? value, string? error = null)
    {
        Web.CoreWebView2?.PostWebMessageAsJson(JsonSerializer.Serialize(new { id, value, error }));
    }
}
