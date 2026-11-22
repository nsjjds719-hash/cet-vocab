using System.Threading;
using System.Windows;

namespace Cijian.Windows;

public partial class App : Application
{
    private Mutex? singleInstance;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        singleInstance = new Mutex(true, @"Local\Cijian.Windows.Spelling", out bool created);
        if (!created)
        {
            MessageBox.Show("词间已经打开。", "词间");
            Shutdown();
            return;
        }
        new MainWindow().Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        singleInstance?.Dispose();
        base.OnExit(e);
    }
}
